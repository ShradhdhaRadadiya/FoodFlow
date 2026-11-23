package com.foodflow.data.repository

import com.foodflow.core.common.AppResult
import com.foodflow.core.common.DispatcherProvider
import com.foodflow.core.common.ErrorMapper
import com.foodflow.core.datastore.UserPreferences
import com.foodflow.core.datastore.UserPreferencesDataStore
import com.foodflow.data.local.dao.CartDao
import com.foodflow.data.local.dao.OrderDao
import com.foodflow.data.local.dao.PendingOperationDao
import com.foodflow.data.local.dao.SearchHistoryDao
import com.foodflow.data.local.database.DatabaseSeeder
import com.foodflow.data.remote.api.UserRemoteDataSource
import com.foodflow.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val userRemoteDataSource: UserRemoteDataSource,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val seeder: DatabaseSeeder,
    private val cartDao: CartDao,
    private val pendingOperationDao: PendingOperationDao,
    private val orderDao: OrderDao,
    private val searchHistoryDao: SearchHistoryDao,
    private val dispatchers: DispatcherProvider
) : AuthRepository {

    override val preferences: Flow<UserPreferences> = preferencesDataStore.preferences

    /** Seeds local catalog data for the current user when needed. */
    override suspend fun ensureSeeded(): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            val currentUserId = preferencesDataStore.getSnapshot().userId ?: DatabaseSeeder.DEFAULT_USER_ID
            seeder.seedIfEmpty(currentUserId)
            AppResult.Success(Unit)
        } catch (t: Throwable) {
            AppResult.Error(ErrorMapper.map(t))
        }
    }

    /** Requests a sign-in code through the configured user data source. */
    override suspend fun requestOtp(phoneNumber: String): AppResult<Int> = withContext(dispatchers.io) {
        try {
            userRemoteDataSource.sendOtp(phoneNumber)
        } catch (t: Throwable) {
            AppResult.Error(ErrorMapper.map(t))
        }
    }

    /** Verifies the sign-in code, saves the session, and seeds that user's local data. */
    override suspend fun verifyOtp(phoneNumber: String, otp: String): AppResult<UserPreferences> =
        withContext(dispatchers.io) {
            try {
                when (val res = userRemoteDataSource.verifyOtp(phoneNumber, otp)) {
                    is AppResult.Success -> {
                        val (userId, token) = res.data
                        val cleanPhone = phoneNumber.filter { it.isDigit() }
                        preferencesDataStore.saveSession(
                            userId = userId,
                            name = "Aarav Sharma",
                            phone = "+91 $cleanPhone",
                            email = "aarav.sharma@foodflow.in",
                            token = token
                        )
                        seeder.seedIfEmpty(userId)
                        AppResult.Success(preferencesDataStore.getSnapshot())
                    }
                    is AppResult.Error -> res
                }
            } catch (t: Throwable) {
                AppResult.Error(ErrorMapper.map(t))
            }
        }

    /** Clears user-specific local data and ends the saved session. */
    override suspend fun logout(): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            val userId = preferencesDataStore.getSnapshot().userId ?: DatabaseSeeder.DEFAULT_USER_ID
            cartDao.clearUserCart(userId)
            pendingOperationDao.clearForUser(userId)
            orderDao.clearCheckoutAttemptsForUser(userId)
            searchHistoryDao.clearForUser(userId)
            preferencesDataStore.clearSession()
            AppResult.Success(Unit)
        } catch (t: Throwable) {
            AppResult.Error(ErrorMapper.map(t))
        }
    }
}
