package com.example.model

data class AuthState(
    val loginUrl: String = "",
    val accessToken: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
