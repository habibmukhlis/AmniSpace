# Dynamic Distraction Adder & Remover Tool Pricing and Synchronization

Enable users to configure flexible commitment prices for the **Distraction Adder** (`0` to `10` gems/coins, defaulting to `0`) and **Distraction Remover** (`20` to `500` gems/coins, recommending `50–100` and defaulting to `50`) tools during initial onboarding, and enforce synchronized tool consumption or instant buy-and-use verification across the Store, Inventory, Screentime Stats, and Per-App Usage Breakdown views.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following product and UX decisions incorporate your latest feedback and govern how distraction management and tool pricing operate across the app:

- **Confirmed Decision 1 — Tool Consumption & Instant Buy-and-Use Flow**: When adding or removing a distracting app from the Screentime Stats or Per-App Breakdown screens, the app checks if the corresponding tool (**Distraction Adder** or **Distraction Remover**) is owned in Inventory or if its configured price is `0`. If owned, it consumes `1` tool from Inventory; if not owned and priced above `0`, an interactive **Instant Buy & Use** confirmation modal allows the user to purchase and apply the tool on the spot using their balance.
- **Confirmed Decision 2 — Flexible Onboarding Commitment Setup & Defaults**:
  - **Distraction Adder**: Users can select a price from **`0` (Free) up to `10` gems/coins** (`0` selected by default, including if the user skips the step).
  - **Distraction Remover**: Users can select a price from **`20` (minimum) up to `500` gems/coins**, with **`50` to `100` highlighted as the recommended range** and **`50` selected by default** if the user skips the step—allowing users to set high-commitment barriers (up to `500`) that make impulsive unblocking virtually impossible.
  - Actual tool purchases happen later in the Store or via instant buy-and-use dialogs.
- **Confirmed Decision 3 — Anti-Cheat Post-Onboarding Price Adjustments**: After onboarding is completed, users can increase their **Distraction Adder** (up to `10`) or **Distraction Remover** (up to `500`) prices anytime for free to strengthen self-control. However, lowering either tool's cost requires paying the current **Distraction Remover** price (or consuming a **Distraction Remover** tool) so users cannot bypass their commitment penalty for free.

---

## 1. Overview & Core Concept

- **What It Does**: Replaces static, hardcoded tool prices and unrestricted blocklist toggles with a unified, user-configured commitment economy. Every point where a user adds or removes a distracting app—whether in the Store, Inventory, Screentime Overview, or Per-App Usage Breakdown—reads the user's custom **Distraction Adder** (`0–10`) and **Distraction Remover** (`20–500`) prices in real time and enforces tool ownership or coin/gem payment.
- **Target Audience / Persona**: Users practicing intentional digital wellbeing and gamified self-control who want customizable friction against impulse unblocking—from moderate accountability (`50–100` coins/gems) to near-ironclad prevention (`500` coins/gems)—while keeping app blocking either effortless (`0` cost) or deliberate (`1–10` cost).
- **Key Value**: Eliminates the loophole where distracting apps could be removed without penalty on the Screentime and Breakdown screens, while keeping the Store, Inventory, and Usage screens 100% synchronized.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Onboarding Commitment Configuration**: Alongside selecting initial distracting apps during onboarding, a dedicated **Commitment & Tool Pricing** card lets the user configure:
     - **Distraction Adder Cost (`0` to `10` Gems/Coins)**: Defaults to `0 (Free)` (preserved if the user skips onboarding). Includes quick preset chips (`0 Free`, `2`, `5`, `10`) and a `0–10` slider.
     - **Distraction Remover Cost (`20` to `500` Gems/Coins)**: Defaults to `50` (preserved if the user skips onboarding), with a visual badge highlighting **`50–100 Recommended`** and quick-select presets (`20`, `50 (Default)`, `100`, `250`, `500 (Ironclad)`) alongside a stepped slider (`20` to `500`).
  2. **Store & Inventory Synchronization**: Opening the **Store** under the *Tools* category displays the user's active dynamic price for both **Distraction Adder** (`0–10`) and **Distraction Remover** (`20–500`). If **Distraction Adder** is configured as `0`, the Store badge clearly shows `FREE (0)` and allows instant acquisition/usage. Opening the **Inventory** dialog and using a **Distraction Adder** or **Distraction Remover** launches the app selector in `ALLOW_ADD` or `ALLOW_REMOVE` mode and deducts the item upon use.
  3. **Screentime & Per-App Breakdown Gated Actions**:
     - Tapping **Block / Add to Distractions** on any app card or inside the Per-App Breakdown checks the user's **Distraction Adder** price:
       - If price is `0`, the app is added to the blocklist immediately with a confirmation toast/snackbar.
       - If price is `1–10`, the app checks if the user owns a **Distraction Adder** in Inventory. If owned, it prompts to consume `1` **Distraction Adder** (`Owned: X`); if none are owned, it presents an **Instant Buy & Use (`X` Gems/Coins)** dialog.
     - Tapping **Remove from Distractions / Unblock** on a blocked app (or unchecking a blocked app in the Manage Distracting Apps dialog) always requires the **Distraction Remover**:
       - Displays a friction confirmation modal showing the user's inventory count (`Distraction Remover Owned: X`) and their configured removal cost (`20–500 Gems/Coins`).
       - Users can either **Use 1 Inventory Tool** (if `Owned > 0`) or **Buy & Use Now** for their configured price (if their balance is sufficient). If they lack both the tool and sufficient balance, the removal is blocked with a clear prompt to complete quests first.
  4. **Commitment Pricing Settings & Anti-Cheat Guard**: From the Screentime header menu (and Store/Account settings), users can open **Distraction Tool Pricing** to adjust their Adder (`0–10`) and Remover (`20–500`) costs. Increasing difficulty is immediate and free; decreasing difficulty displays a warning badge and requires paying the current **Distraction Remover** cost (or consuming `1` **Distraction Remover** from Inventory) to confirm.

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Intentional, high-contrast gamified utility combining Material 3 dark surface depth with clear semantic status badges for commitment actions.
  - *Color Palette & Mood*: Uses `MaterialTheme.colorScheme.surface` and layered `surfaceVariant` cards, paired with `primary` / `tertiary` accents for **Distraction Adder** protection badges and `error` / amber warning accents for **Distraction Remover** friction dialogs.
  - *Typography & Hierarchy*: Bold display numerals (`headlineSmall` / `titleLarge`) for gem/coin prices on sliders and confirmation modals, paired with readable `bodyMedium` explanations of commitment tiers (e.g., *Recommended: 50–100*, *Maximum Prevention: 500*).
  - *Component Styling & Layout*: 8dp spatial grid with rounded 16dp M3 cards, quick-select filter chips paired with smooth M3 `Slider` controls for `0–10` Adder and `20–500` Remover costs, and minimum 48dp touch targets across all quick-toggle buttons.
- **Interactive Feedback & Motion**: Immediate balance and inventory counter updates via reactive state flows, spring-animated confirmation dialogs, and descriptive snackbars/toasts whenever a tool is consumed or purchased (`"Used 1 Distraction Remover (-1 Inventory)"` or `"Purchased & used Distraction Remover (-50 Coins)"`).

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Wide `20–500` Distraction Remover Range with `50` Default & `50–100` Recommendation**
  - *Chosen Approach*: Allow users to pick any Remover cost from `20` up to `500`, recommend `50–100` in the UI, and default to `50` (even if onboarding is skipped).
  - *Why*: Gives casual users a sensible, balanced starting point (`50`) while empowering strict self-control users to set a `250–500` coin barrier that acts as a true lock against impulse unblocking.
  - *Alternatives Considered*: Capping the Remover price at `100` was rejected because `100` coins can still be accumulated relatively quickly by active users, whereas `500` provides an ironclad commitment tier.
- **Decision 2: Flexible `0–10` Distraction Adder Range with `0` Default**
  - *Chosen Approach*: Allow users to pick any Adder cost from `0` (Free) up to `10`, defaulting to `0` if untouched or skipped.
  - *Why*: Lets users keep adding distractions completely frictionless (`0`) by default, or add a small, customizable intentionality cost (`1–10`) so blocklist additions are thoughtful.
- **Decision 3: Asymmetric Friction for Post-Onboarding Price Changes**
  - *Chosen Approach*: Increasing tool prices is always free, whereas lowering tool prices requires paying the current **Distraction Remover** cost (or consuming a Remover tool).
  - *Why*: Prevents users from bypassing a `500`-coin removal barrier by opening settings and lowering the price to `20` for free during a moment of temptation.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌───────────────────────────────────────────────────────────────────────────┐
│                         UserInfo & UserRepository                         │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │ UserInfo / CustomizationInfo                                        │  │
│  │  • distractionAdderPrice: Int (0..10, default = 0)                  │  │
│  │  • distractionRemoverPrice: Int (20..500, default = 50)             │  │
│  │  • inventory: HashMap<InventoryItem, Int>                           │  │
│  │  • coinsState: MutableStateFlow<Int>                                │  │
│  │  • blockedAndroidPackages: Set<String>                              │  │
│  └──────────────────────────────────┬──────────────────────────────────┘  │
│                                     │                                     │
│  • getEffectiveItemPrice(item: InventoryItem): Int                        │
│  • updateDistractionToolPrices(adderPrice, removerPrice)                  │
│  • tryUseOrBuyDistractionTool(item, onSuccess, onInsufficientFunds)       │
└──────────┬──────────────────────────┼──────────────────────────┬──────────┘
           │                          │                          │
           ▼                          ▼                          ▼
┌─────────────────────┐   ┌──────────────────────┐   ┌──────────────────────┐
│  Onboarding Setup   │   │  Store & Inventory   │   │  Screentime & App    │
│                     │   │                      │   │  Usage Breakdown     │
│ • SelectApps Step   │   │ • StoreViewModel     │   │ • ScreentimeStats    │
│ • Adder Slider/Chip:│   │   reads dynamic      │   │   App Row & Dialogs  │
│   0..10 (Def: 0)    │   │   Adder/Remover cost │   │ • AppUsageBreakdown  │
│ • Remover Slider:   │   │ • InventoryBox       │   │   Block/Unblock CTA  │
│   20..500 (Def: 50, │   │   consumes item &    │   │ • Tool Gate Modal    │
│   Rec: 50..100)     │   │   opens SelectApps   │   │   (Use Tool or       │
│ • Persists to       │   │   in ADD/REMOVE mode │   │    Instant Buy+Use)  │
│   UserRepository    │   │                      │   │                      │
└─────────────────────┘   └──────────────────────┘   └──────────────────────┘
```

### Data Model & State

- **Dynamic Tool Pricing Persistence**:
  - Extend user profile persistence (`UserInfo` / `CustomizationInfo` with `@Serializable` defaults for backward compatibility with existing saved profiles and cloud sync) to store:
    - `distractionAdderPrice: Int = 0` (constrained to `0..10`, default `0`).
    - `distractionRemoverPrice: Int = 50` (constrained to `20..500`, default `50`).
- **Centralized Price Resolution in `UserRepository`**:
  - Add helper methods in `UserRepository` so every screen queries a single source of truth:
    - `getEffectivePrice(item: InventoryItem): Int` returns `userInfo.customization_info.distractionAdderPrice.coerceIn(0, 10)` for `InventoryItem.DISTRACTION_ADDER`, `userInfo.customization_info.distractionRemoverPrice.coerceIn(20, 500)` for `InventoryItem.DISTRACTION_REMOVER`, and `item.price` for all other items.
    - `setDistractionToolPrices(adderPrice: Int, removerPrice: Int)` validates bounds (`0..10` for Adder; `20..500` for Remover), updates `userInfo`, emits updated state flows, and saves to `SharedPreferences`.

### Interactive Component & State Mapping

- **Onboarding (`SelectApps` Subscreen)**:
  - Displays a prominent **Distraction Tool Pricing** configuration card at the top of the app selection list when used in onboarding (`SelectAppsModes.ALLOW_ADD_AND_REMOVE`).
  - Initializes with `distractionAdderPrice = 0` and `distractionRemoverPrice = 50` by default so even if the user skips the step, `0` and `50` are active.
  - Provides a `0–10` control for **Distraction Adder** and a `20–500` slider + preset chips (`20`, `50`, `100`, `250`, `500`) with a **"Recommended: 50–100"** indicator for **Distraction Remover**.
- **Store (`StoreViewModel` & `StoreScreen`)**:
  - `StoreViewModel.getItemPrice(item: InventoryItem)` queries `userRepository.getEffectivePrice(item)`.
  - `hasEnoughCoinsToPurchaseItem(item)` and `makeItemPurchase(item)` use the dynamic price instead of the static enum default.
  - Both `StoreItemCard` and `PurchaseItemDialog` display the live user-selected price (`0–10` for Adder, `20–500` for Remover).
- **Screentime & Per-App Usage Breakdown (`ScreentimeStatsScreen` & `AppUsageBreakdownScreen`)**:
  - **Adding an App to Distractions**:
    - If `adderPrice == 0`: Immediately adds the package to `blockedPackages`, syncs `AppBlockerService`, and shows confirmation feedback.
    - If `adderPrice in 1..10`: Opens the **Distraction Tool Action Dialog** for `InventoryItem.DISTRACTION_ADDER`. The user can either spend `1` owned `DISTRACTION_ADDER` from Inventory or pay `adderPrice` (`1–10`) coins/gems to buy and apply it immediately.
  - **Removing an App from Distractions**:
    - Always opens the **Distraction Tool Action Dialog** for `InventoryItem.DISTRACTION_REMOVER`.
    - Shows current `DISTRACTION_REMOVER` inventory count (`Owned: N`), current user balance, and the configured removal cost (`20–500`).
    - **Action A (Owned > 0)**: Deducts `1` `DISTRACTION_REMOVER` from Inventory and removes the app from the blocklist.
    - **Action B (Owned == 0, Balance >= Cost)**: Deducts `removerPrice` (`20–500`) coins/gems from `userRepository` and removes the app from the blocklist.
    - **Action C (Insufficient Balance & 0 Owned)**: Disables confirmation and displays how many more coins/gems are needed.
  - **Bulk Manage Distracting Apps Dialog**:
    - Calculates the net number of newly added apps (`addedCount`) and removed apps (`removedCount`) compared to the currently saved `blockedPackages`.
    - If `removedCount > 0` or (`addedCount > 0 && adderPrice > 0`), calculates total required Adder/Remover tools and prompts the user with the exact combined tool/coin cost before committing changes.
  - **Post-Onboarding Tool Price Settings Dialog**:
    - Accessible from the Screentime top bar menu (`Tool Pricing Settings`).
    - Allows raising Adder (`current` → higher value up to `10`) or Remover (`current` → higher value up to `500`) immediately for free.
    - If the new Adder price is lower than the current saved Adder price or the new Remover price is lower than the current saved Remover price, requires consuming `1` `DISTRACTION_REMOVER` or paying the *current* `distractionRemoverPrice` before saving the reduction.
