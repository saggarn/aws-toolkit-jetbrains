// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.core.credentials

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import software.amazon.awssdk.services.ssooidc.model.SsoOidcException
import software.aws.toolkits.core.ClientConnectionSettings
import software.aws.toolkits.core.ConnectionSettings
import software.aws.toolkits.core.TokenConnectionSettings
import software.aws.toolkits.core.credentials.ToolkitBearerTokenProvider
import software.aws.toolkits.jetbrains.core.credentials.pinning.FeatureWithPinnedConnection
import software.aws.toolkits.jetbrains.core.credentials.sono.ALL_AVAILABLE_SCOPES
import software.aws.toolkits.jetbrains.core.credentials.sso.bearer.BearerTokenAuthState
import software.aws.toolkits.jetbrains.core.credentials.sso.bearer.BearerTokenProvider
import software.aws.toolkits.jetbrains.core.credentials.sso.bearer.BearerTokenProviderListener
import software.aws.toolkits.jetbrains.core.credentials.sso.bearer.DEFAULT_SSO_REGION
import software.aws.toolkits.jetbrains.utils.runUnderProgressIfNeeded
import software.aws.toolkits.resources.message

sealed interface ToolkitConnection {
    val id: String
    val label: String

    fun getConnectionSettings(): ClientConnectionSettings<*>
}

interface AwsCredentialConnection : ToolkitConnection {
    override fun getConnectionSettings(): ConnectionSettings
}

interface AwsBearerTokenConnection : ToolkitConnection {
    override fun getConnectionSettings(): TokenConnectionSettings
}

interface BearerSsoConnection : AwsBearerTokenConnection {
    val scopes: List<String>
}

sealed interface AuthProfile

data class ManagedSsoProfile(
    var ssoRegion: String,
    var startUrl: String,
    var scopes: List<String>
) : AuthProfile {
    // only used for deserialization
    constructor() : this("", "", emptyList())
}

interface ToolkitAuthManager {
    fun listConnections(): List<ToolkitConnection>

    fun createConnection(profile: AuthProfile): ToolkitConnection

    fun deleteConnection(connection: ToolkitConnection)
    fun deleteConnection(connectionId: String)

    fun getConnection(connectionId: String): ToolkitConnection?

    companion object {
        fun getInstance() = service<ToolkitAuthManager>()
    }
}

interface ToolkitConnectionManager {
    fun activeConnection(): ToolkitConnection?

    fun activeConnectionForFeature(feature: FeatureWithPinnedConnection): ToolkitConnection?

    fun switchConnection(connection: ToolkitConnection?)

    companion object {
        fun getInstance(project: Project) = project.service<ToolkitConnectionManager>()
    }
}

/**
 * Individual service should subscribe [ToolkitConnectionManagerListener.TOPIC] to fire their service activation / UX update
 */
fun loginSso(project: Project, startUrl: String, scopes: List<String> = ALL_AVAILABLE_SCOPES): BearerTokenProvider {
    val connectionId = ToolkitBearerTokenProvider.ssoIdentifier(startUrl)
    val manager = ToolkitAuthManager.getInstance()

    return manager.getConnection(connectionId)?.let {
        // There is an existing connection we can use

        // For the case when the existing connection is in invalid state, we need to re-auth
        if (it is AwsBearerTokenConnection) {
            val tokenProvider = reauthProviderIfNeeded(it)

            ToolkitConnectionManager.getInstance(project).switchConnection(it)

            return tokenProvider
        }

        null
    } ?: run {
        // No existing connection, start from scratch
        val connection = manager.createConnection(
            ManagedSsoProfile(
                DEFAULT_SSO_REGION,
                startUrl,
                scopes
            )
        )

        try {
            val provider = reauthProviderIfNeeded(connection)

            ToolkitConnectionManager.getInstance(project).switchConnection(connection)

            provider
        } catch (e: Exception) {
            manager.deleteConnection(connection)
            throw e
        }
    }
}

fun reauthProviderIfNeeded(connection: ToolkitConnection): BearerTokenProvider {
    val tokenProvider = (connection.getConnectionSettings() as TokenConnectionSettings).tokenProvider.delegate as BearerTokenProvider
    val state = tokenProvider.state()
    runUnderProgressIfNeeded(null, message("settings.states.validating.short"), false) {
        if (state == BearerTokenAuthState.NEEDS_REFRESH) {
            try {
                tokenProvider.resolveToken()
                BearerTokenProviderListener.notifyCredUpdate(tokenProvider.id)
            } catch (e: SsoOidcException) {
                tokenProvider.reauthenticate()
            }
        } else if (state == BearerTokenAuthState.NOT_AUTHENTICATED) {
            tokenProvider.reauthenticate()
        }

        Unit
    }

    return tokenProvider
}
