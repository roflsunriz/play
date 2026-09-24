package io.github.playmusic.data.auth

class AuthException(message: String, val requiresLogin: Boolean = false) : Exception(message)

class LoginVerificationRequiredException(
    val username: String,
    val challenge: Login5Client.LoginOutcome.CodeChallengeRequired,
) : Exception("A verification code is required to renew the session")
