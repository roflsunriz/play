package io.github.playmusic.data.auth

class SpotifyAuthException(message: String, val requiresLogin: Boolean = false) : Exception(message)

class LoginVerificationRequiredException(
    val username: String,
    val challenge: SpotifyLogin5Client.LoginOutcome.CodeChallengeRequired,
) : Exception("A verification code is required to renew the session")
