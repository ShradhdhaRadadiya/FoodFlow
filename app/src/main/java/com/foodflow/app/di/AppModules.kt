package com.foodflow.app.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.foodflow.core.common.DefaultDispatcherProvider
import com.foodflow.core.common.DispatcherProvider
import com.foodflow.core.connectivity.AndroidConnectivityObserver
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.core.websocket.LocalSimulatedOrderEventSource
import com.foodflow.core.websocket.OrderEventSource
import com.foodflow.data.local.dao.AddressDao
import com.foodflow.data.local.dao.CartDao
import com.foodflow.data.local.dao.CatalogDao
import com.foodflow.data.local.dao.FavoriteDao
import com.foodflow.data.local.dao.MockServerStateDao
import com.foodflow.data.local.dao.OrderDao
import com.foodflow.data.local.dao.PendingOperationDao
import com.foodflow.data.local.dao.ReviewDao
import com.foodflow.data.local.dao.ScheduledOrderDao
import com.foodflow.data.local.dao.SearchHistoryDao
import com.foodflow.data.local.dao.SupportTicketDao
import com.foodflow.data.local.database.FoodFlowDatabase
import com.foodflow.data.mock.LocalMockBackend
import com.foodflow.data.mock.scheduled.MockFoodItemAvailabilityDataSourceImpl
import com.foodflow.data.mock.scheduled.MockLatestPricingDataSourceImpl
import com.foodflow.data.mock.scheduled.MockRestaurantAvailabilityDataSourceImpl
import com.foodflow.data.mock.scheduled.MockScheduledOrderExecutionDataSourceImpl
import com.foodflow.data.mock.scheduled.MockScheduledPaymentValidationDataSourceImpl
import com.foodflow.data.remote.api.CartRemoteDataSource
import com.foodflow.data.remote.api.FoodItemAvailabilityDataSource
import com.foodflow.data.remote.api.LatestPricingDataSource
import com.foodflow.data.remote.api.OrderRemoteDataSource
import com.foodflow.data.remote.api.PaymentRemoteDataSource
import com.foodflow.data.remote.api.RestaurantAvailabilityDataSource
import com.foodflow.data.remote.api.RestaurantRemoteDataSource
import com.foodflow.data.remote.api.ReviewRemoteDataSource
import com.foodflow.data.remote.api.ScheduledOrderExecutionDataSource
import com.foodflow.data.remote.api.ScheduledPaymentValidationDataSource
import com.foodflow.data.remote.api.UserRemoteDataSource
import com.foodflow.data.repository.AddressRepositoryImpl
import com.foodflow.data.repository.AuthRepositoryImpl
import com.foodflow.data.repository.CartRepositoryImpl
import com.foodflow.data.repository.DeliveryRouteRepositoryImpl
import com.foodflow.data.repository.OrderRepositoryImpl
import com.foodflow.data.repository.OrderTrackingRepositoryImpl
import com.foodflow.data.repository.PaymentRepositoryImpl
import com.foodflow.data.repository.RestaurantRepositoryImpl
import com.foodflow.data.repository.ReviewRepositoryImpl
import com.foodflow.data.repository.ScheduledOrderRepositoryImpl
import com.foodflow.data.repository.SupportRepositoryImpl
import com.foodflow.data.repository.UserPreferencesRepositoryImpl
import com.foodflow.data.repository.VoiceAssistantRepositoryImpl
import com.foodflow.data.sync.ScheduledOrderWorkScheduler
import com.foodflow.data.sync.WorkManagerScheduledOrderSchedulerImpl
import com.foodflow.data.voice.AndroidVoiceAudioFocusCoordinatorImpl
import com.foodflow.data.voice.AndroidVoiceAudioPlaybackDataSourceImpl
import com.foodflow.data.voice.AndroidVoiceAudioRecordingDataSourceImpl
import com.foodflow.data.voice.FirebaseGeminiLiveWebSocketDataSourceImpl
import com.foodflow.data.voice.FirebaseGeminiTextChatDataSourceImpl
import com.foodflow.data.voice.FoodFlowAssistantActionExecutorImpl
import com.foodflow.data.voice.RuleBasedFoodAssistantFallback
import com.foodflow.data.voice.VoiceAssistantActionDataSource
import com.foodflow.data.voice.VoiceAssistantSessionManager
import com.foodflow.data.voice.VoiceAudioFocusCoordinator
import com.foodflow.data.voice.VoiceAudioPlaybackDataSource
import com.foodflow.data.voice.VoiceAudioRecordingDataSource
import com.foodflow.data.voice.VoiceConversationDataSource
import com.foodflow.data.voice.VoiceConversationStoreImpl
import com.foodflow.data.voice.VoiceLocalFallbackEngine
import com.foodflow.data.voice.VoiceTextChatDataSource
import com.foodflow.data.voice.VoiceWebSocketDataSource
import com.foodflow.domain.repository.AddressRepository
import com.foodflow.domain.repository.AuthRepository
import com.foodflow.domain.repository.CartRepository
import com.foodflow.domain.repository.DeliveryRouteRepository
import com.foodflow.domain.repository.OrderRepository
import com.foodflow.domain.repository.OrderTrackingRepository
import com.foodflow.domain.repository.PaymentRepository
import com.foodflow.domain.repository.RestaurantRepository
import com.foodflow.domain.repository.ReviewRepository
import com.foodflow.domain.repository.ScheduledOrderRepository
import com.foodflow.domain.repository.SupportRepository
import com.foodflow.domain.repository.UserPreferencesRepository
import com.foodflow.domain.repository.VoiceAssistantController
import com.foodflow.domain.repository.VoiceAssistantRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    /** Provides shared lenient JSON settings so remote or mock payloads can omit unknown fields. */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /** Builds the Room database with explicit schema migrations and exposes the app's local source of truth. */
    @Provides
    @Singleton
    fun provideFoodFlowDatabase(@ApplicationContext context: Context): FoodFlowDatabase =
        Room.databaseBuilder(context, FoodFlowDatabase::class.java, FoodFlowDatabase.DB_NAME)
            .addMigrations(
                FoodFlowDatabase.MIGRATION_1_2,
                FoodFlowDatabase.MIGRATION_2_3,
                FoodFlowDatabase.MIGRATION_3_4
            )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    /** Provides the DAO used to read and update catalog tables. */
    @Provides fun provideCatalogDao(db: FoodFlowDatabase): CatalogDao = db.catalogDao()
    /** Provides the DAO used to persist the user's cart. */
    @Provides fun provideCartDao(db: FoodFlowDatabase): CartDao = db.cartDao()
    /** Provides the DAO for queued offline cart operations. */
    @Provides fun providePendingOperationDao(db: FoodFlowDatabase): PendingOperationDao = db.pendingOperationDao()
    /** Provides the DAO for orders, checkout attempts, and tracking timelines. */
    @Provides fun provideOrderDao(db: FoodFlowDatabase): OrderDao = db.orderDao()
    /** Provides the DAO for saved delivery addresses. */
    @Provides fun provideAddressDao(db: FoodFlowDatabase): AddressDao = db.addressDao()
    /** Provides the DAO for restaurant favorite state. */
    @Provides fun provideFavoriteDao(db: FoodFlowDatabase): FavoriteDao = db.favoriteDao()
    /** Provides the DAO for recent search history. */
    @Provides fun provideSearchHistoryDao(db: FoodFlowDatabase): SearchHistoryDao = db.searchHistoryDao()
    /** Provides the DAO for locally stored support tickets. */
    @Provides fun provideSupportTicketDao(db: FoodFlowDatabase): SupportTicketDao = db.supportTicketDao()
    /** Provides the DAO backing persistent mock-server state. */
    @Provides fun provideMockServerStateDao(db: FoodFlowDatabase): MockServerStateDao = db.mockServerStateDao()
    /** Provides the DAO for restaurant reviews and rating aggregates. */
    @Provides fun provideReviewDao(db: FoodFlowDatabase): ReviewDao = db.reviewDao()
    /** Provides the DAO for scheduled orders and execution history. */
    @Provides fun provideScheduledOrderDao(db: FoodFlowDatabase): ScheduledOrderDao = db.scheduledOrderDao()

    /** Provides the process-wide WorkManager instance used by background jobs. */
    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    /** Builds the shared HTTP client with bounded connection/read timeouts and basic request logging. */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    /** Builds Retrofit with the app API base URL and shared HTTP client. */
    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("https://api.foodflow.local/")
        .client(okHttpClient)
        .build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {

    /** Binds the app's coroutine dispatcher implementation for injectable background execution. */
    @Binds @Singleton
    abstract fun bindDispatcherProvider(impl: DefaultDispatcherProvider): DispatcherProvider

    /** Binds Android network monitoring to the connectivity abstraction. */
    @Binds @Singleton
    abstract fun bindConnectivityObserver(impl: AndroidConnectivityObserver): ConnectivityObserver

    /** Uses local simulated order events as the current tracking event source. */
    @Binds @Singleton
    abstract fun bindOrderEventSource(impl: LocalSimulatedOrderEventSource): OrderEventSource

    // Replaceable remote data source bindings — swap LocalMockBackend with real Retrofit impls anytime
    /** Uses the mock backend for restaurant catalog requests behind the remote contract. */
    @Binds @Singleton
    abstract fun bindRestaurantRemote(impl: LocalMockBackend): RestaurantRemoteDataSource

    /** Uses the mock backend for cart synchronization behind the remote contract. */
    @Binds @Singleton
    abstract fun bindCartRemote(impl: LocalMockBackend): CartRemoteDataSource

    /** Uses the mock backend for order operations behind the remote contract. */
    @Binds @Singleton
    abstract fun bindOrderRemote(impl: LocalMockBackend): OrderRemoteDataSource

    /** Uses the mock backend for payment operations behind the remote contract. */
    @Binds @Singleton
    abstract fun bindPaymentRemote(impl: LocalMockBackend): PaymentRemoteDataSource

    /** Uses the mock backend for sign-in and favorite synchronization. */
    @Binds @Singleton
    abstract fun bindUserRemote(impl: LocalMockBackend): UserRemoteDataSource

    /** Uses the mock backend for restaurant review operations. */
    @Binds @Singleton
    abstract fun bindReviewRemote(impl: LocalMockBackend): ReviewRemoteDataSource

    // Separate replaceable scheduled-order mock data sources
    /** Binds mock restaurant open-hours and delivery-slot checks. */
    @Binds @Singleton
    abstract fun bindRestaurantAvailabilityDataSource(
        impl: MockRestaurantAvailabilityDataSourceImpl
    ): RestaurantAvailabilityDataSource

    /** Binds mock food-item availability checks for scheduled orders. */
    @Binds @Singleton
    abstract fun bindFoodItemAvailabilityDataSource(
        impl: MockFoodItemAvailabilityDataSourceImpl
    ): FoodItemAvailabilityDataSource

    /** Binds mock latest-price quotes used before scheduled placement. */
    @Binds @Singleton
    abstract fun bindLatestPricingDataSource(
        impl: MockLatestPricingDataSourceImpl
    ): LatestPricingDataSource

    /** Binds mock payment authorization checks for scheduled execution. */
    @Binds @Singleton
    abstract fun bindScheduledPaymentValidationDataSource(
        impl: MockScheduledPaymentValidationDataSourceImpl
    ): ScheduledPaymentValidationDataSource

    /** Binds mock execution of each due scheduled-order occurrence. */
    @Binds @Singleton
    abstract fun bindScheduledOrderExecutionDataSource(
        impl: MockScheduledOrderExecutionDataSourceImpl
    ): ScheduledOrderExecutionDataSource

    /** Binds WorkManager scheduling for future scheduled-order occurrences. */
    @Binds @Singleton
    abstract fun bindScheduledOrderWorkScheduler(
        impl: WorkManagerScheduledOrderSchedulerImpl
    ): ScheduledOrderWorkScheduler

    // Domain repositories
    /** Binds authentication operations to their data-layer implementation. */
    @Binds @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    /** Binds saved-address operations to their data-layer implementation. */
    @Binds @Singleton
    abstract fun bindAddressRepository(impl: AddressRepositoryImpl): AddressRepository

    /** Binds catalog and restaurant operations to their data-layer implementation. */
    @Binds @Singleton
    abstract fun bindRestaurantRepository(impl: RestaurantRepositoryImpl): RestaurantRepository

    /** Binds cart operations to their data-layer implementation. */
    @Binds @Singleton
    abstract fun bindCartRepository(impl: CartRepositoryImpl): CartRepository

    /** Binds checkout and order operations to their data-layer implementation. */
    @Binds @Singleton
    abstract fun bindOrderRepository(impl: OrderRepositoryImpl): OrderRepository

    /** Binds payment operations to their data-layer implementation. */
    @Binds @Singleton
    abstract fun bindPaymentRepository(impl: PaymentRepositoryImpl): PaymentRepository

    /** Binds live order-tracking operations to their data-layer implementation. */
    @Binds @Singleton
    abstract fun bindOrderTrackingRepository(impl: OrderTrackingRepositoryImpl): OrderTrackingRepository

    /** Binds review operations to their data-layer implementation. */
    @Binds @Singleton
    abstract fun bindReviewRepository(impl: ReviewRepositoryImpl): ReviewRepository

    /** Binds schedule lifecycle operations to their data-layer implementation. */
    @Binds @Singleton
    abstract fun bindScheduledOrderRepository(impl: ScheduledOrderRepositoryImpl): ScheduledOrderRepository

    /** Binds support-ticket operations to their data-layer implementation. */
    @Binds @Singleton
    abstract fun bindSupportRepository(impl: SupportRepositoryImpl): SupportRepository

    /** Binds user preference operations to their data-layer implementation. */
    @Binds @Singleton
    abstract fun bindUserPreferencesRepository(impl: UserPreferencesRepositoryImpl): UserPreferencesRepository

    /** Binds invoice generation to the Android PDF implementation. */
    @Binds @Singleton
    abstract fun bindInvoicePdfGenerator(
        impl: com.foodflow.core.invoice.AndroidInvoicePdfGenerator
    ): com.foodflow.core.invoice.InvoicePdfGenerator

    /** Binds delivery-location observation to Room-backed location data. */
    @Binds @Singleton
    abstract fun bindDeliveryLocationProvider(
        impl: com.foodflow.core.location.RoomDeliveryLocationProvider
    ): com.foodflow.core.location.DeliveryLocationProvider

    /** Binds route lookup to its data-layer implementation. */
    @Binds @Singleton
    abstract fun bindDeliveryRouteRepository(
        impl: DeliveryRouteRepositoryImpl
    ): DeliveryRouteRepository

    // Global Real-Time AI Voice Assistant (single shared instances across the entire app)
    /** Binds the Firebase Gemini Live WebSocket transport for audio conversations. */
    @Binds @Singleton
    abstract fun bindVoiceWebSocketDataSource(
        impl: FirebaseGeminiLiveWebSocketDataSourceImpl
    ): VoiceWebSocketDataSource

    /** Binds the Firebase Gemini text transport used for typed prompts. */
    @Binds @Singleton
    abstract fun bindVoiceTextChatDataSource(
        impl: FirebaseGeminiTextChatDataSourceImpl
    ): VoiceTextChatDataSource

    /** Binds Android microphone capture for voice input. */
    @Binds @Singleton
    abstract fun bindVoiceAudioRecordingDataSource(
        impl: AndroidVoiceAudioRecordingDataSourceImpl
    ): VoiceAudioRecordingDataSource

    /** Binds Android PCM playback for assistant speech. */
    @Binds @Singleton
    abstract fun bindVoiceAudioPlaybackDataSource(
        impl: AndroidVoiceAudioPlaybackDataSourceImpl
    ): VoiceAudioPlaybackDataSource

    /** Binds the shared conversation and resumption-context store. */
    @Binds @Singleton
    abstract fun bindVoiceConversationDataSource(
        impl: VoiceConversationStoreImpl
    ): VoiceConversationDataSource

    /** Binds the executor that maps model tool calls to existing app actions. */
    @Binds @Singleton
    abstract fun bindVoiceAssistantActionDataSource(
        impl: FoodFlowAssistantActionExecutorImpl
    ): VoiceAssistantActionDataSource

    /** Binds Android audio-focus and output-route coordination. */
    @Binds @Singleton
    abstract fun bindVoiceAudioFocusCoordinator(
        impl: AndroidVoiceAudioFocusCoordinatorImpl
    ): VoiceAudioFocusCoordinator

    /** Binds local catalog-backed responses when Gemini is unavailable. */
    @Binds @Singleton
    abstract fun bindVoiceLocalFallbackEngine(
        impl: RuleBasedFoodAssistantFallback
    ): VoiceLocalFallbackEngine

    /** Binds the application-scoped coordinator that owns the assistant session lifecycle. */
    @Binds @Singleton
    abstract fun bindVoiceAssistantController(
        impl: VoiceAssistantSessionManager
    ): VoiceAssistantController

    /** Exposes the session coordinator through the domain repository contract. */
    @Binds @Singleton
    abstract fun bindVoiceAssistantRepository(
        impl: VoiceAssistantRepositoryImpl
    ): VoiceAssistantRepository
}
