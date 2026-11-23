package com.foodflow.core

import android.database.sqlite.SQLiteException
import com.foodflow.core.common.AppError
import com.foodflow.core.common.ErrorMapper
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ErrorMapperTest {

    @Test
    fun `maps UnknownHostException and ConnectException to DnsFailure and ConnectionTimeout`() {
        val unknownHost = ErrorMapper.map(UnknownHostException("api.foodflow.in"))
        val connect = ErrorMapper.map(ConnectException("Failed to connect"))

        assertTrue(unknownHost is AppError.DnsFailure)
        assertTrue(connect is AppError.ConnectionTimeout)
        assertTrue(unknownHost.isRetryable)
        assertTrue(connect.isRetryable)
    }

    @Test
    fun `maps SocketTimeoutException to Timeout`() {
        val mapped = ErrorMapper.map(SocketTimeoutException("Read timed out"))
        assertTrue(mapped is AppError.Timeout)
        assertTrue(mapped.isRetryable)
    }

    @Test
    fun `maps SerializationException to SerializationError with clean non-confusing copy`() {
        val mapped = ErrorMapper.map(SerializationException("Unexpected token"))
        assertTrue(mapped is AppError.SerializationError)
        assertFalse(mapped.userMessage.contains("unexpected response", ignoreCase = true))
        assertFalse(mapped.userMessage.contains("mock", ignoreCase = true))
    }

    @Test
    fun `maps SQLiteException to DatabaseError and generic IOException to Offline`() {
        val dbErr = ErrorMapper.map(SQLiteException("disk full"))
        val ioErr = ErrorMapper.map(IOException("broken pipe"))

        assertTrue(dbErr is AppError.DatabaseError)
        assertTrue(ioErr is AppError.Offline)
    }

    @Test
    fun `maps HTTP status codes to structured domain AppErrors`() {
        assertTrue(ErrorMapper.fromHttpCode(400) is AppError.HttpBadRequest)
        assertTrue(ErrorMapper.fromHttpCode(401) is AppError.Unauthorized)
        assertTrue(ErrorMapper.fromHttpCode(404) is AppError.NotFound)
        assertTrue(ErrorMapper.fromHttpCode(409) is AppError.Conflict)
        assertTrue(ErrorMapper.fromHttpCode(503) is AppError.ServerError)
    }

    @Test(expected = CancellationException::class)
    fun `rethrows CancellationException to preserve structured concurrency`() {
        ErrorMapper.map(CancellationException("cancelled"))
    }
}
