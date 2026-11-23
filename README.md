# FoodFlow — Offline-First Indian Food Delivery Android App

**FoodFlow** is a production-grade, offline-first Indian food delivery Android application (inspired by Swiggy, Zomato, and EatSure) built in Kotlin and Jetpack Compose with **Clean Architecture + MVI**, **Room as Single Source of Truth**, **Transactional Outbox + WorkManager background sync**, **Idempotent Order Creation**, **Simulated WebSocket Live Tracking**, and a **Stateful Local Mock Backend**.

---

## 1. App Overview

FoodFlow delivers a complete end-to-end customer ordering experience with resilient local caching and fault-tolerant checkout:
- **Discovery & Personalization**: Location switcher, cuisine categories, promo offers, popular/recommended/top-rated kitchens, active order banner, and recently ordered dishes.
- **Offline-First Search & Browsing**: Debounced (`250ms` + `flatMapLatest`) local Room search across 15 restaurants and 105 Indian/global dishes with Veg-only, cuisine, price (`Under ₹250`), rating (`4.5+`), and delivery time filters.
- **Resilient Cart & Coupons**: Single-restaurant cart enforcement with conflict prompt, dish add-ons, cooking instructions, live GST (5%), packaging charges, free-delivery threshold (₹499), and 10 coupons (percentage, flat, free-delivery, expired validation).
- **Idempotent Checkout & Payment Simulation**: Persisted `CheckoutAttempt` with stable `idempotencyKey`, timeout-after-write recovery (`GET /orders/by-idempotency-key/{key}`), offline checkout protection, and UPI/Card/COD payment simulation with duplicate callback deduplication.
- **Real-Time Order Tracking**: Deterministic simulated WebSocket updating Room, Canvas-rendered animated delivery route map (no Google Maps API key required), rider card, ETA, timeline, and sequence-number deduplication.
- **Orders, Favourites, Profile, Support & Developer Scenario Lab**: Past/active order history with one-tap reorder and star ratings, saved favourites, dark/light/system theme toggle, 6 customer support workflows, and an in-app **Developer Scenario Lab** to inject network faults, payment states, stock/price conflicts, and WebSocket edge cases.

---

## 2. Architecture Diagram

```text
+-----------------------------------------------------------------------------+
|                        PRESENTATION LAYER (MVI + Compose)                   |
|  18 Screens (Splash, Auth, Home, Search, Detail, Cart, Checkout, Payment,   |
|  Confirmation, Tracking, Orders, Favorites, Profile, Support, Debug Lab)    |
|     UI Intents ---> ViewModel (StateFlow<State>, SharedFlow<Event>)         |
+------------------------------------+----------------------------------------+
                                     |
                                     v
+-----------------------------------------------------------------------------+
|                              DOMAIN LAYER                                   |
|  Pure Kotlin Models | OrderStatus State Machine | Repository Interfaces     |
|  UseCases: CalculateCartTotals, ValidateCoupon, ValidateOrderTransition     |
+------------------------------------+----------------------------------------+
                                     |
                                     v
+-----------------------------------------------------------------------------+
|                               DATA LAYER                                    |
|  Repositories (Room SSOT) <-------> WorkManager (CartSyncWorker Outbox)     |
|      |                     |                                                |
|      v                     v                                                |
|  +-----------------------+ +----------------------------------------------+ |
|  |   ROOM DATABASE       | |  REMOTE DATA SOURCES (*RemoteDataSource)     | |
|  |   (Single Source of   | |  [Current Impl]: LocalMockBackend            | |
|  |    Truth - 14 Tables) | |   + MockScenarioController (Fault Injection) | |
|  |   + DataStore Prefs   | |   + LocalSimulatedOrderEventSource (WS)      | |
|  +-----------------------+ |  [Prod Swap]: Retrofit FoodFlowApi + OkHttp  | |
|                            +----------------------------------------------+ |
+-----------------------------------------------------------------------------+
```

---

## 3. Tech Stack & Justification

| Technology | Role | Rationale |
|---|---|---|
| **Kotlin + Coroutines + Flow** | Language & Concurrency | Structured concurrency, reactive Room streams, clean `StateFlow`/`SharedFlow` MVI state pipelines. |
| **Jetpack Compose + Material 3** | UI & Design System | Declarative UI with adaptive `NavigationBar`/`NavigationRail` (`BoxWithConstraints`), custom typography (`Outfit`, `Plus Jakarta Sans`, `JetBrains Mono`), and edge-to-edge rendering. |
| **Clean Architecture + MVI** | App Architecture | Strict separation of pure domain logic from Android/Room/Network frameworks; unidirectional data flow (`Intent -> ViewModel -> State/Event`) prevents inconsistent UI states. |
| **Hilt (`@HiltAndroidApp`, `@HiltWorker`)** | Dependency Injection | Compile-time DI wiring across Repositories, DAOs, ViewModels, and WorkManager workers; makes swapping `LocalMockBackend` for Retrofit a one-module change. |
| **Room Database** | Local SSOT & Outbox | Reactive `Flow` queries, `@Transaction` atomic outbox writes, unique idempotency indexes, and offline catalog persistence. |
| **WorkManager** | Deferred Background Sync | Survives app backgrounding and process death; enforces `NetworkType.CONNECTED` constraints, unique work deduplication (`KEEP`), and exponential backoff. |
| **Preferences DataStore** | Session & App Preferences | Type-safe asynchronous persistence for auth session, selected address, theme mode, and notification toggles. |
| **Kotlinx Serialization** | JSON & Type-Safe Routes | Zero-reflection JSON parsing for `foodflow_seed.json`, mock server state snapshots, and `@Serializable` Navigation Compose routes. |
| **Retrofit + OkHttp** | Production API Blueprint | Complete REST contract interface (`FoodFlowApi`) and DTOs ready for live backend swap without altering domain/UI layers. |
| **JUnit 4 + MockK + Turbine + Robolectric** | Automated Testing | Fast local JVM verification of domain math, Room DAOs, WorkManager retry logic, MVI ViewModels, and Jetpack Compose UI components. |

---

## 4. Package Structure

```text
com.example
└── MainActivity.kt                      # @AndroidEntryPoint, Edge-to-Edge, Theme observer

com.foodflow
├── app
│   ├── FoodFlowApp.kt                   # @HiltAndroidApp, WorkManager Configuration.Provider
│   ├── di/AppModules.kt                 # Hilt modules (Database, Mock/Remote, Connectivity, WS)
│   └── navigation/FoodFlowNavigation.kt # Type-safe @Serializable NavHost + Adaptive Chrome
├── core
│   ├── common/                          # AppResult, AppError, Money, DispatcherProvider, MviBase
│   ├── connectivity/                    # ConnectivityObserver (Android NetworkCallback + override)
│   ├── datastore/                       # UserPreferencesDataStore (session, theme, prefs)
│   ├── designsystem/                    # Color, Type, Theme, Reusable M3 FoodFlowComponents
│   └── websocket/                       # OrderEventSource + LocalSimulatedOrderEventSource
├── domain
│   ├── model/DomainModels.kt            # Pure Kotlin models + OrderStatus/PaymentStatus enums
│   ├── repository/Repositories.kt       # Repository contracts (Auth, Restaurant, Cart, Order, Tracking)
│   └── usecase/UseCases.kt              # CalculateCartTotals, ValidateCoupon, ValidateOrderTransition
├── data
│   ├── local/
│   │   ├── entity/Entities.kt           # 14 Room tables (Catalog, Cart, Outbox, Orders, MockServerState)
│   │   ├── dao/Daos.kt                  # CatalogDao, CartDao (@Transaction), PendingOperationDao, OrderDao...
│   │   ├── database/FoodFlowDatabase.kt # Room DB + DatabaseSeeder (loads assets/foodflow_seed.json)
│   │   └── mapper/Mappers.kt            # Entity <-> Domain <-> DTO mappers
│   ├── remote/
│   │   ├── api/FoodFlowApi.kt           # Retrofit blueprint + *RemoteDataSource interfaces
│   │   └── dto/Dtos.kt                  # @Serializable API request/response DTOs
│   ├── mock/
│   │   ├── MockScenarioController.kt    # Deterministic fault/latency/payment/stock controller
│   │   └── LocalMockBackend.kt          # Stateful local mock server persisted across restarts
│   ├── sync/CartSyncWorker.kt           # @HiltWorker WorkManager outbox drainer
│   └── repository/                      # Auth/Address, Restaurant, Cart, Order/Payment, OrderTracking impls
└── feature
    ├── splash/                          # Splash & initial seed/session bootstrap
    ├── auth/                            # Phone + Mock OTP (123456) login
    ├── location/                        # Saved addresses, serviceable check, add/edit/delete
    ├── home/                            # Discovery home, categories, offers, active order banner
    ├── search/                          # Debounced local search + recent history + filters
    ├── restaurant/                      # Restaurant list + Restaurant detail & menu + add-ons sheet
    ├── cart/                            # Cart, coupon sheet, price breakdown, sync badges
    ├── checkout/                        # Idempotent checkout, address & payment selection
    ├── payment/                         # UPI/Card/COD simulation, pending verify, retry
    ├── orderconfirmation/               # Order confirmed summary + receipt
    ├── tracking/                        # Live WebSocket tracking + Canvas route map + timeline
    ├── orders/                          # Active & past orders + reorder + rating dialog
    ├── favorites/                       # Saved kitchens
    ├── profile/                         # User profile, theme selector, preferences, Debug Lab entry
    ├── support/                         # 6 structured help & support workflows + ticket history
```

---

## 5. Screen Flow Diagram

```text
[Splash]
   ├── (not logged in) ──> [Login (Mock OTP 123456)] ──> [Home]
   └── (logged in) ────────────────────────────────────> [Home]

Top-Level Navigation (Bottom NavigationBar / Tablet NavigationRail):
  [Home] <──> [Search] <──> [Cart] <──> [Orders] <──> [Profile]

Primary Ordering Funnel:
  [Home] ──> [Restaurant List] ──> [Restaurant Detail & Menu]
                                           │ (Add Items + Add-ons)
                                           v
  [Cart] ──(Validate & Sync)──> [Checkout (Idempotent Key)]
                                           │
                                           v
  [Payment Simulation (UPI/Card/COD)] ──> [Order Confirmation]
                                           │
                                           v
                                [Live Order Tracking (WS + Canvas Map)]

Secondary Flows:
  [Home / Checkout / Profile] ──> [Location & Addresses]
  [Home / Profile] ──────────────> [Favourites]
  [Orders / Profile] ────────────> [Help & Support (6 Workflows)]
  [Home / Profile] ──────────────> [Developer Scenario Lab]
```

---

## 6. Offline-First Architecture Explanation

1. **Room as Single Source of Truth (SSOT)**: Every screen observes Room `Flow`s exclusively. Remote refreshes write into Room (`OnConflictStrategy.REPLACE`) and never bypass the database to push transient lists directly into UI state.
2. **Never Wipe Cache on Failure**: When `refreshHomeCatalog()` or `refreshMenu()` encounters offline state, timeout, HTTP 500, or malformed JSON, existing cached Room records remain intact and visible while a non-blocking banner/snackbar informs the user.
3. **Seeded Offline-Ready Dataset**: `assets/foodflow_seed.json` seeds 8 categories, 15 restaurants, 105 food items, 10 coupons, 5 delivery addresses, 3 delivery partners, and 16 orders on first launch alongside bundled local food photography drawables.
4. **Offline Capabilities vs Safety Boundaries**:
   - **Allowed offline**: Browse restaurants/menus, search, filter, toggle favourites, add/edit/remove cart items, apply cached coupons, inspect past orders and receipts.
   - **Safely blocked offline**: Order confirmation and payment settlement require authoritative server verification so an offline device never falsely displays an order as confirmed.

---

## 7. Background Sync Workflow (Transactional Outbox + WorkManager)

1. **Atomic Outbox Write (`CartDao.@Transaction`)**: Every cart mutation (`addItem`, `updateQuantity`, `removeItem`, `clearCart`) writes BOTH the `cart_items` row (`syncStatus = PENDING`) and a `pending_operations` row (with a unique `operationId`) inside a single SQLite transaction. Process death can never create local drift without an outbox entry.
2. **Immediate or Deferred Dispatch**: If online, `CartRepositoryImpl` drains the outbox immediately under a `Mutex`. If offline (or on retryable failure), `CartSyncWorker.enqueue(workManager)` schedules unique work (`UNIQUE_WORK_NAME = "foodflow_cart_outbox_sync"`, `ExistingWorkPolicy.KEEP`) constrained to `NetworkType.CONNECTED` with exponential backoff (`10s` base, max 5 attempts).
3. **Operation Coalescing & Server Deduplication**: Before transmitting, obsolete intermediate edits for the same `(entityType, userId, itemId)` are coalesced so stale quantities are never replayed. Each transmitted operation carries its stable `operationId`; `LocalMockBackend` tracks `processedCartOperationIds` so lost ACKs are safely acknowledged without double-application.
4. **Conflict Reconciliation**: Server responses carry authoritative `authoritativePrice`, `itemAvailable`, and `restaurantOpen` flags. Price changes update Room automatically and surface `CartReconciliationNotice.PriceChanged`; out-of-stock items are removed with `CartReconciliationNotice.ItemUnavailable`.

---

## 8. Duplicate Order Prevention & Idempotency Strategy

1. **Persisted `CheckoutAttempt`**: Before calling `POST /orders`, `OrderRepositoryImpl.startOrResumeCheckout` creates and persists a `CheckoutAttemptEntity` in Room with a UUID `idempotencyKey`. Subsequent retries for the same restaurant and total reuse that exact persisted attempt.
2. **UI & Repository Concurrency Guards**: `CheckoutViewModel` locks out repeated taps while `isSubmitting == true`, and `OrderRepositoryImpl` guards submission inside a `Mutex`.
3. **Timeout-After-Write Recovery**:
   - Suppose `POST /orders` succeeds on the server, persists order `FF-2026-9101` keyed by `idempotencyKey`, and then times out in transit (`SimulatedNetworkFault.TIMEOUT_AFTER_SERVER_WRITE`).
   - When the user taps **Retry**, `submitIdempotentOrder` first checks local Room (`getOrderByIdempotencyKey`), then invokes `GET /orders/by-idempotency-key/{key}`, and finally sends idempotent `POST /orders` with the same key.
   - The backend returns the original `FF-2026-9101` instead of creating a second order.
4. **Independent Payment State & Callback Dedup**: `PaymentStatus` (`NOT_STARTED`, `PENDING`, `SUCCESS`, `FAILED`, `REFUND_PENDING`, `REFUNDED`, `COD_DUE`) is strictly decoupled from `OrderStatus`. Duplicate payment callbacks sharing the same `callbackId` are ignored idempotently by `LocalMockBackend`.

---

## 9. Real-Time WebSocket Tracking Simulation

- **Abstraction**: `OrderEventSource` exposes `connectionState: StateFlow<WebSocketConnectionState>` and `events: Flow<OrderEvent>`. `LocalSimulatedOrderEventSource` implements a deterministic lifecycle script (`ORDER_ACCEPTED -> FOOD_PREPARING -> FOOD_READY -> DELIVERY_PARTNER_ASSIGNED -> ORDER_PICKED_UP -> RIDER_LOCATION_UPDATED x3 -> ORDER_DELIVERED`).
- **Room-First Updates**: `OrderTrackingRepositoryImpl` consumes `OrderEvent` frames and writes order status, rider coordinates (`riderLat`, `riderLng`, `riderProgress`), ETA, and `order_timeline_events` directly into Room.
- **Ordering, Deduplication & State-Machine Guards**:
  - Events for mismatched `orderId` are discarded.
  - Events with `sequenceNumber == lastEventSequence` (duplicates) or `sequenceNumber < lastEventSequence` (stale/out-of-order) are rejected and counted in tracking diagnostics.
  - `OrderStatus.canTransitionTo()` blocks illegal transitions (such as `PLACED -> DELIVERED` or transitions out of terminal states).
- **Reconnect & REST Reconciliation**: On disconnect/reconnect, `reconcileOrder()` calls `GET /orders/{id}` to reconcile missed status updates without regressing local progress.
- **Lifecycle Safety**: Leaving the tracking screen triggers `stopTracking()` to cancel coroutine collectors and close the active socket cleanly.

---

## 10. Mock Backend Design & Swapping to a Real Backend

`LocalMockBackend` implements all five `*RemoteDataSource` interfaces (`RestaurantRemoteDataSource`, `CartRemoteDataSource`, `OrderRemoteDataSource`, `PaymentRemoteDataSource`, `UserRemoteDataSource`) and persists simulated server-side tables (`idempotencyOrders`, `processedCartOperationIds`, `processedPaymentCallbacks`, `orderPayments`) in Room's `mock_server_state` table so server state survives restarts and can be reset independently of client cache.

**How to switch to a real backend**:
1. Implement `Retrofit*RemoteDataSource` classes delegating to `FoodFlowApi` (already defined in `data/remote/api/FoodFlowApi.kt`).
2. Implement `OkHttpOrderEventSource` using `OkHttpClient.newWebSocket(...)` against `OrderEventSource`.
3. Update the `@Binds` bindings in `app/di/AppModules.kt` (`RemoteDataSourceModule`) to point from `LocalMockBackend` / `LocalSimulatedOrderEventSource` to the Retrofit / OkHttp implementations. **Zero changes are required in Domain, ViewModels, or Compose UI.**

---

## 11. Local Room Database Schema Summary (`FoodFlowDatabase`, v1)

| Table | Primary Key | Purpose |
|---|---|---|
| `categories` | `id` | 8 cuisine categories for discovery & filtering |
| `restaurants` | `id` | 15 restaurant listings with ratings, ETA, fees, coordinates |
| `food_items` | `id` | 105 dishes indexed by `restaurantId` with JSON add-ons & veg/stock flags |
| `coupons` | `code` | 10 promo coupons (percentage, flat, free-delivery, expired) |
| `cart_items` | `(itemId, userId)` | Local offline-editable cart with `syncStatus` |
| `pending_operations` | `operationId` | Transactional outbox for deferred WorkManager sync |
| `orders` | `id` (unique `idempotencyKey`) | Order history, active order state, rider coords, `lastEventSequence` |
| `order_timeline_events` | `id` | Ordered audit log of order & tracking milestones |
| `checkout_attempts` | `idempotencyKey` | Persisted in-flight checkout attempts preventing duplicates |
| `addresses` | `id` | Saved delivery addresses with coordinates & serviceability flag |
| `favorites` | `(userId, restaurantId)` | User's saved favourite kitchens with sync status |
| `search_history` | `(userId, query)` | Recent search queries |
| `support_tickets` | `id` | Customer support requests and mock resolutions |
| `mock_server_state` | `key` | Independently resettable mock backend persisted snapshot |

---

## 12. API Contract Summary (`FoodFlowApi`)

- **Auth**: `POST /auth/otp/send`, `POST /auth/otp/verify`
- **Discovery**: `GET /restaurants`, `GET /restaurants/{id}/menu`, `GET /coupons/validate`
- **Cart Outbox**: `POST /cart/operations` (idempotent via `operationId`), `POST /cart/validate`
- **Orders**: `POST /orders` (idempotent via `idempotencyKey`), `GET /orders/by-idempotency-key/{key}`, `GET /orders`, `GET /orders/{id}`, `POST /orders/{id}/cancel`
- **Payments**: `POST /payments/process` (deduped via `callbackId`), `GET /payments/verify`
- **User**: `PUT /users/{userId}/favorites/{restaurantId}`
- **WebSocket**: `wss://api.foodflow.in/ws/orders/{orderId}` (abstracted via `OrderEventSource`)

---

## 13. How to Run the Project

1. Open the project in Android Studio (AGP `9.1.1`, Kotlin `2.4.21`, JDK 17+, `minSdk 24`, `targetSdk 36`).
2. Run the app on an Android emulator or device — no API keys, Firebase config, or external server are required.
3. On the Login screen, enter any 10-digit Indian phone number (or use the pre-filled demo number) and enter OTP **`123456`**.
4. Run all local JVM unit, Room DAO, WorkManager, ViewModel, and Compose UI tests:
   ```bash
   gradle :app:testDebugUnitTest
   ```

---

## 14. How to Test Offline Mode

1. Open **Developer Scenario Lab** (from the lab icon in Home top bar or **Profile -> Developer Scenario Lab**).
2. Toggle **Force Offline Mode** ON (or turn on device Airplane Mode).
3. Observe the persistent **Offline Mode · Cached Data Active** banner on Home, Search, and Cart.
4. Browse menus, search dishes, toggle favourites, and add/edit cart items — items persist immediately in Room with `Pending Sync` badges and queue outbox operations.
5. Attempt to place an order while offline — checkout safely blocks confirmation while keeping your cart intact.
6. Toggle **Force Offline Mode** OFF (or reconnect network) — WorkManager / immediate sync drains the outbox and reconciles any server price/stock changes automatically.

---

## 15. How to Trigger Mock Failures & Fault Scenarios

Open **Developer Scenario Lab** to deterministically test every edge case:
- **Network Faults**: `NONE`, `TIMEOUT`, `TIMEOUT_AFTER_SERVER_WRITE` (proves idempotent order recovery on retry), `SERVER_ERROR_500`, `INVALID_JSON_RESPONSE`.
- **Payment Outcomes**: `SUCCESS`, `FAILED` (allows retry without duplicate order), `PENDING` (allows polling `Verify Payment Status`).
- **Cart Reconciliation Conflicts**: Toggle **Item Out of Stock** or **Simulate Price Increase** on popular dishes, edit cart offline, then reconnect to watch live reconciliation notices.
- **Kitchen & Auth Edge Cases**: Toggle **Restaurant Rejects Order**, **Delayed Order Mode**, **Expired OTP Mode**, and adjustable simulated network latency (`0ms`–`2500ms`).
- **Live Tracking Controls** (on Order Tracking screen): **Advance Step**, **Disconnect / Reconnect + Reconcile**, **Inject Duplicate Event**, and **Inject Stale Out-of-Order Event** with live diagnostic counters.
- **State Resets**: Independently **Reset Local Client Cache** vs **Reset Mock Server State**.

---

## 16. Known Limitations

- **Local Mock Backend Only**: Data is persisted locally on-device in Room (`mock_server_state`) and does not sync across separate physical devices.
- **Simulated Payments**: UPI, Card, and COD flows are deterministic local simulations; no real payment gateway or PCI handling is performed.
- **Simulated WebSocket & Canvas Map**: Live tracking events are generated locally by `LocalSimulatedOrderEventSource`, and the delivery route map is rendered with Jetpack Compose `Canvas` to avoid requiring a Google Maps API key.
- **Mock OTP Authentication**: Login accepts demo OTP `123456` locally without SMS delivery.

---

## 17. Future Enhancements

- Bind `FoodFlowApi` (Retrofit + OkHttp interceptors + JWT token refresh) and `OkHttpOrderEventSource` (`okhttp3.WebSocket`) to a live cloud backend.
- Add Paging 3 (`RemoteMediator`) for large multi-city restaurant catalogs.
- Integrate Google Maps Compose SDK / MapLibre with polyline decoding for real GPS rider coordinates.
- Add Push Notifications (FCM) for background order milestone alerts and deep-linking to `OrderTrackingRoute`.
- Add biometric / passkey checkout confirmation and multi-currency formatting support.
