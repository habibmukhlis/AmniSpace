cmake_minimum_required(VERSION 3.22.1)
project("sentencepiece_jni" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(SP_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../sentencepiece")

if(EXISTS "${SP_ROOT}/src/sentencepiece_processor.cc")
    message(STATUS "Building sentencepiece_jni with libsentencepiece submodule sources")

    file(GLOB SP_PROTO_SRCS
        "${SP_ROOT}/src/builtin_pb/*.cc"
        "${SP_ROOT}/third_party/protobuf-lite/*.cc"
    )

    set(SP_CORE_SRCS
        "${SP_ROOT}/src/bpe_model.cc"
        "${SP_ROOT}/src/char_model.cc"
        "${SP_ROOT}/src/error.cc"
        "${SP_ROOT}/src/filesystem.cc"
        "${SP_ROOT}/src/model_factory.cc"
        "${SP_ROOT}/src/model_interface.cc"
        "${SP_ROOT}/src/normalizer.cc"
        "${SP_ROOT}/src/sentencepiece_processor.cc"
        "${SP_ROOT}/src/unigram_model.cc"
        "${SP_ROOT}/src/util.cc"
        "${SP_ROOT}/src/word_model.cc"
        "${SP_ROOT}/third_party/absl/flags/flag.cc"
        "${SP_ROOT}/third_party/absl/strings/string_view.cc"
    )

    add_library(
        sentencepiece_jni
        SHARED
        sentencepiece_jni.cpp
        ${SP_PROTO_SRCS}
        ${SP_CORE_SRCS}
    )

    target_include_directories(
        sentencepiece_jni
        PRIVATE
        "${SP_ROOT}"
        "${SP_ROOT}/src"
        "${SP_ROOT}/src/builtin_pb"
        "${SP_ROOT}/third_party"
        "${SP_ROOT}/third_party/protobuf-lite"
    )

    target_compile_definitions(
        sentencepiece_jni
        PRIVATE
        _USE_INTERNAL_STRING_VIEW
        HAVE_PTHREAD=1
    )
else()
    message(STATUS "Building sentencepiece_jni with standalone embedded SentencePiece implementation")

    add_library(
        sentencepiece_jni
        SHARED
        sentencepiece_jni.cpp
    )
endif()

find_library(log-lib log)

target_link_libraries(
    sentencepiece_jni
    ${log-lib}
)
