#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <unordered_map>
#include <fstream>
#include <mutex>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <algorithm>

#define LOG_TAG "SentencePieceJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if __has_include("sentencepiece_processor.h")
#include "sentencepiece_processor.h"
#define HAS_SP_HEADER 1
#else
#define HAS_SP_HEADER 0
#endif

namespace {

std::mutex g_mutex;
std::string g_loaded_model_path;
bool g_is_loaded = false;

#if HAS_SP_HEADER
sentencepiece::SentencePieceProcessor g_processor;

bool loadModelInternal(const std::string& model_path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_is_loaded && g_loaded_model_path == model_path) {
        return true;
    }
    const auto status = g_processor.Load(model_path);
    if (!status.ok()) {
        LOGE("Failed to load SentencePiece model at %s: %s",
             model_path.c_str(), status.ToString().c_str());
        g_is_loaded = false;
        g_loaded_model_path.clear();
        return false;
    }
    g_loaded_model_path = model_path;
    g_is_loaded = true;
    LOGI("Loaded SentencePiece model via libsentencepiece: %s", model_path.c_str());
    return true;
}

std::vector<int> encodeAsIdsInternal(const std::string& text) {
    std::lock_guard<std::mutex> lock(g_mutex);
    std::vector<int> ids;
    if (!g_is_loaded) {
        LOGE("encodeAsIds called before SentencePiece model was loaded");
        return ids;
    }
    const auto status = g_processor.Encode(text, &ids);
    if (!status.ok()) {
        LOGE("Failed to encode text: %s", status.ToString().c_str());
        return {};
    }
    return ids;
}

void unloadInternal() {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_is_loaded = false;
    g_loaded_model_path.clear();
}

#else

// Embedded standalone SentencePiece ModelProto reader & Viterbi/BPE tokenizer
struct PieceEntry {
    std::string piece;
    float score = 0.0f;
    int type = 1; // 1=NORMAL, 2=UNKNOWN, 3=CONTROL, 4=USER_DEFINED, 5=UNUSED, 6=BYTE
};

struct TrieNode {
    std::unordered_map<unsigned char, int> children;
    int token_id = -1;
    float score = -1e10f;
};

std::vector<PieceEntry> g_pieces;
std::vector<TrieNode> g_trie;
int g_byte_tokens[256];
int g_unk_id = 3;
bool g_add_dummy_prefix = false;
bool g_remove_extra_whitespaces = false;
bool g_escape_whitespaces = true;

uint64_t readVarint(const uint8_t* data, size_t size, size_t& pos) {
    uint64_t result = 0;
    int shift = 0;
    while (pos < size && shift < 64) {
        uint8_t byte = data[pos++];
        result |= static_cast<uint64_t>(byte & 0x7F) << shift;
        if ((byte & 0x80) == 0) break;
        shift += 7;
    }
    return result;
}

void skipField(const uint8_t* data, size_t size, size_t& pos, int wire_type) {
    switch (wire_type) {
        case 0:
            readVarint(data, size, pos);
            break;
        case 1:
            pos = std::min(size, pos + 8);
            break;
        case 2: {
            uint64_t len = readVarint(data, size, pos);
            pos = std::min(size, pos + static_cast<size_t>(len));
            break;
        }
        case 5:
            pos = std::min(size, pos + 4);
            break;
        default:
            pos = size;
            break;
    }
}

PieceEntry parseSentencePiece(const uint8_t* data, size_t end) {
    PieceEntry entry;
    size_t pos = 0;
    while (pos < end) {
        uint64_t tag = readVarint(data, end, pos);
        int field_num = static_cast<int>(tag >> 3);
        int wire_type = static_cast<int>(tag & 0x07);
        if (field_num == 1 && wire_type == 2) {
            uint64_t len = readVarint(data, end, pos);
            if (pos + len <= end) {
                entry.piece.assign(reinterpret_cast<const char*>(data + pos), static_cast<size_t>(len));
            }
            pos = std::min(end, pos + static_cast<size_t>(len));
        } else if (field_num == 2 && wire_type == 5) {
            if (pos + 4 <= end) {
                uint32_t bits = static_cast<uint32_t>(data[pos]) |
                                (static_cast<uint32_t>(data[pos + 1]) << 8) |
                                (static_cast<uint32_t>(data[pos + 2]) << 16) |
                                (static_cast<uint32_t>(data[pos + 3]) << 24);
                std::memcpy(&entry.score, &bits, sizeof(float));
            }
            pos = std::min(end, pos + 4);
        } else if (field_num == 3 && wire_type == 0) {
            entry.type = static_cast<int>(readVarint(data, end, pos));
        } else {
            skipField(data, end, pos, wire_type);
        }
    }
    return entry;
}

void parseNormalizerSpec(const uint8_t* data, size_t end) {
    size_t pos = 0;
    while (pos < end) {
        uint64_t tag = readVarint(data, end, pos);
        int field_num = static_cast<int>(tag >> 3);
        int wire_type = static_cast<int>(tag & 0x07);
        if (field_num == 3 && wire_type == 0) {
            g_add_dummy_prefix = (readVarint(data, end, pos) != 0);
        } else if (field_num == 4 && wire_type == 0) {
            g_remove_extra_whitespaces = (readVarint(data, end, pos) != 0);
        } else if (field_num == 5 && wire_type == 0) {
            g_escape_whitespaces = (readVarint(data, end, pos) != 0);
        } else {
            skipField(data, end, pos, wire_type);
        }
    }
}

bool loadModelInternal(const std::string& model_path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_is_loaded && g_loaded_model_path == model_path) {
        return true;
    }

    std::ifstream file(model_path, std::ios::binary | std::ios::ate);
    if (!file.is_open()) {
        LOGE("Cannot open tokenizer model file: %s", model_path.c_str());
        return false;
    }
    std::streamsize file_size = file.tellg();
    if (file_size <= 0) {
        LOGE("Tokenizer model file is empty: %s", model_path.c_str());
        return false;
    }
    file.seekg(0, std::ios::beg);
    std::vector<uint8_t> buffer(static_cast<size_t>(file_size));
    if (!file.read(reinterpret_cast<char*>(buffer.data()), file_size)) {
        LOGE("Failed to read tokenizer model file: %s", model_path.c_str());
        return false;
    }

    g_pieces.clear();
    g_trie.clear();
    g_trie.emplace_back();
    std::fill(std::begin(g_byte_tokens), std::end(g_byte_tokens), -1);
    g_unk_id = 3;
    g_add_dummy_prefix = false;
    g_remove_extra_whitespaces = false;
    g_escape_whitespaces = true;

    size_t pos = 0;
    const size_t total = buffer.size();
    while (pos < total) {
        uint64_t tag = readVarint(buffer.data(), total, pos);
        int field_num = static_cast<int>(tag >> 3);
        int wire_type = static_cast<int>(tag & 0x07);
        if (field_num == 1 && wire_type == 2) {
            uint64_t len = readVarint(buffer.data(), total, pos);
            if (pos + len <= total) {
                g_pieces.push_back(parseSentencePiece(buffer.data() + pos, static_cast<size_t>(len)));
            }
            pos = std::min(total, pos + static_cast<size_t>(len));
        } else if (field_num == 3 && wire_type == 2) {
            uint64_t len = readVarint(buffer.data(), total, pos);
            if (pos + len <= total) {
                parseNormalizerSpec(buffer.data() + pos, static_cast<size_t>(len));
            }
            pos = std::min(total, pos + static_cast<size_t>(len));
        } else {
            skipField(buffer.data(), total, pos, wire_type);
        }
    }

    if (g_pieces.empty()) {
        LOGE("No vocabulary pieces found in %s", model_path.c_str());
        return false;
    }

    for (size_t id = 0; id < g_pieces.size(); ++id) {
        const auto& p = g_pieces[id];
        if (p.type == 2) {
            g_unk_id = static_cast<int>(id);
            continue;
        }
        if (p.type == 3 || p.type == 5) {
            continue; // Skip CONTROL (<pad>, <eos>, <bos>) and UNUSED
        }
        if (p.type == 6 && p.piece.size() == 6 && p.piece.rfind("<0x", 0) == 0 && p.piece[5] == '>') {
            unsigned int byte_val = 0;
            if (sscanf(p.piece.c_str(), "<0x%02X>", &byte_val) == 1 ||
                sscanf(p.piece.c_str(), "<0x%02x>", &byte_val) == 1) {
                if (byte_val < 256) {
                    g_byte_tokens[byte_val] = static_cast<int>(id);
                }
            }
            continue;
        }
        if (p.piece.empty()) continue;

        int node_idx = 0;
        for (unsigned char ch : p.piece) {
            auto it = g_trie[node_idx].children.find(ch);
            if (it == g_trie[node_idx].children.end()) {
                int next_idx = static_cast<int>(g_trie.size());
                g_trie.emplace_back();
                g_trie[node_idx].children[ch] = next_idx;
                node_idx = next_idx;
            } else {
                node_idx = it->second;
            }
        }
        g_trie[node_idx].token_id = static_cast<int>(id);
        g_trie[node_idx].score = p.score;
    }

    g_loaded_model_path = model_path;
    g_is_loaded = true;
    LOGI("Loaded %zu SentencePiece pieces from %s", g_pieces.size(), model_path.c_str());
    return true;
}

std::vector<int> encodeAsIdsInternal(const std::string& raw_text) {
    std::lock_guard<std::mutex> lock(g_mutex);
    std::vector<int> result;
    if (!g_is_loaded || raw_text.empty()) {
        return result;
    }

    std::string norm;
    const std::string sp_space = "\xE2\x96\x81"; // U+2581
    if (g_add_dummy_prefix) {
        norm += sp_space;
    }
    bool prev_space = false;
    for (size_t i = 0; i < raw_text.size(); ++i) {
        char c = raw_text[i];
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
            if (g_remove_extra_whitespaces && prev_space) continue;
            prev_space = true;
            if (g_escape_whitespaces) {
                norm += sp_space;
            } else {
                norm.push_back(' ');
            }
        } else {
            prev_space = false;
            norm.push_back(c);
        }
    }

    const size_t n = norm.size();
    if (n == 0) return result;

    std::vector<double> best_score(n + 1, -1e18);
    std::vector<int> best_prev(n + 1, -1);
    std::vector<int> best_token(n + 1, g_unk_id);
    best_score[0] = 0.0;

    for (size_t i = 0; i < n; ++i) {
        if (best_score[i] <= -1e17) continue;
        int node_idx = 0;
        bool matched_any = false;
        for (size_t j = i; j < n; ++j) {
            unsigned char ch = static_cast<unsigned char>(norm[j]);
            auto it = g_trie[node_idx].children.find(ch);
            if (it == g_trie[node_idx].children.end()) break;
            node_idx = it->second;
            if (g_trie[node_idx].token_id >= 0) {
                matched_any = true;
                double cand = best_score[i] + static_cast<double>(g_trie[node_idx].score);
                if (cand > best_score[j + 1]) {
                    best_score[j + 1] = cand;
                    best_prev[j + 1] = static_cast<int>(i);
                    best_token[j + 1] = g_trie[node_idx].token_id;
                }
            }
        }
        // Byte/single-char fallback if no piece matched from position i
        if (!matched_any || best_prev[i + 1] == -1) {
            unsigned char b = static_cast<unsigned char>(norm[i]);
            int fallback_id = (g_byte_tokens[b] >= 0) ? g_byte_tokens[b] : g_unk_id;
            double cand = best_score[i] - 20.0;
            if (cand > best_score[i + 1]) {
                best_score[i + 1] = cand;
                best_prev[i + 1] = static_cast<int>(i);
                best_token[i + 1] = fallback_id;
            }
        }
    }

    int cur = static_cast<int>(n);
    while (cur > 0) {
        result.push_back(best_token[cur]);
        int prev = best_prev[cur];
        if (prev < 0 || prev >= cur) break;
        cur = prev;
    }
    std::reverse(result.begin(), result.end());
    return result;
}

void unloadInternal() {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_pieces.clear();
    g_trie.clear();
    g_loaded_model_path.clear();
    g_is_loaded = false;
}
#endif

jboolean jniLoad(JNIEnv* env, jstring modelPath) {
    if (modelPath == nullptr) return JNI_FALSE;
    const char* path_chars = env->GetStringUTFChars(modelPath, nullptr);
    if (path_chars == nullptr) return JNI_FALSE;
    std::string path_str(path_chars);
    env->ReleaseStringUTFChars(modelPath, path_chars);
    return loadModelInternal(path_str) ? JNI_TRUE : JNI_FALSE;
}

jintArray jniEncodeAsIds(JNIEnv* env, jstring text) {
    if (text == nullptr) {
        return env->NewIntArray(0);
    }
    const char* text_chars = env->GetStringUTFChars(text, nullptr);
    if (text_chars == nullptr) {
        return env->NewIntArray(0);
    }
    std::string text_str(text_chars);
    env->ReleaseStringUTFChars(text, text_chars);

    std::vector<int> ids = encodeAsIdsInternal(text_str);
    jintArray out = env->NewIntArray(static_cast<jsize>(ids.size()));
    if (out != nullptr && !ids.empty()) {
        env->SetIntArrayRegion(out, 0, static_cast<jsize>(ids.size()), ids.data());
    }
    return out;
}

} // namespace

extern "C" {

// Package: neth.iecal.questphone.ai.SentencePieceProcessor
JNIEXPORT jboolean JNICALL
Java_neth_iecal_questphone_ai_SentencePieceProcessor_load(
        JNIEnv* env,
        jobject /* this */,
        jstring modelPath) {
    return jniLoad(env, modelPath);
}

JNIEXPORT jintArray JNICALL
Java_neth_iecal_questphone_ai_SentencePieceProcessor_encodeAsIds(
        JNIEnv* env,
        jobject /* this */,
        jstring text) {
    return jniEncodeAsIds(env, text);
}

JNIEXPORT void JNICALL
Java_neth_iecal_questphone_ai_SentencePieceProcessor_unload(
        JNIEnv* /* env */,
        jobject /* this */) {
    unloadInternal();
}

// Package: com.alhaq.amniquest.ai.SentencePieceProcessor
JNIEXPORT jboolean JNICALL
Java_com_alhaq_amniquest_ai_SentencePieceProcessor_load(
        JNIEnv* env,
        jobject /* this */,
        jstring modelPath) {
    return jniLoad(env, modelPath);
}

JNIEXPORT jintArray JNICALL
Java_com_alhaq_amniquest_ai_SentencePieceProcessor_encodeAsIds(
        JNIEnv* env,
        jobject /* this */,
        jstring text) {
    return jniEncodeAsIds(env, text);
}

JNIEXPORT void JNICALL
Java_com_alhaq_amniquest_ai_SentencePieceProcessor_unload(
        JNIEnv* /* env */,
        jobject /* this */) {
    unloadInternal();
}

} // extern "C"
