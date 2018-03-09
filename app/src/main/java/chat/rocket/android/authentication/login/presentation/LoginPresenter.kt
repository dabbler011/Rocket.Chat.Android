package chat.rocket.android.authentication.login.presentation

import chat.rocket.android.authentication.domain.model.TokenModel
import chat.rocket.android.authentication.presentation.AuthenticationNavigator
import chat.rocket.android.core.lifecycle.CancelStrategy
import chat.rocket.android.helper.NetworkHelper
import chat.rocket.android.helper.UrlHelper
import chat.rocket.android.infrastructure.LocalRepository
import chat.rocket.android.server.domain.*
import chat.rocket.android.server.infraestructure.RocketChatClientFactory
import chat.rocket.android.util.extensions.generateRandomString
import chat.rocket.android.util.extensions.isEmailValid
import chat.rocket.android.util.extensions.launchUI
import chat.rocket.common.RocketChatException
import chat.rocket.common.RocketChatTwoFactorException
import chat.rocket.common.model.Token
import chat.rocket.common.util.ifNull
import chat.rocket.core.RocketChatClient
import chat.rocket.core.internal.rest.*
import javax.inject.Inject

class LoginPresenter @Inject constructor(private val view: LoginView,
                                         private val strategy: CancelStrategy,
                                         private val navigator: AuthenticationNavigator,
                                         private val multiServerRepository: MultiServerTokenRepository,
                                         private val localRepository: LocalRepository,
                                         private val settingsInteractor: GetSettingsInteractor,
                                         private val serverInteractor: GetCurrentServerInteractor,
                                         factory: RocketChatClientFactory) {
    // TODO - we should validate the current server when opening the app, and have a nonnull get()
    private val client: RocketChatClient = factory.create(serverInteractor.get()!!)

    fun setup() {
        val server = serverInteractor.get()
        if (server == null) {
            navigator.toServerScreen()
            return
        }

        val settings = settingsInteractor.get(server)
        if (settings == null) {
            navigator.toServerScreen()
            return
        }

        if (settings.casEnabled()) {
            // Hiding the views in order to show the WebView instead.
            view.hideUsernameOrEmailView()
            view.hidePasswordView()
            view.hideSignUpView()
            view.hideOauthView()
            view.hideLoginButton()

            view.showLoading()
            val token = generateRandomString(17)
            view.showCasView(UrlHelper.getCasUrl(settings.casLoginUrl(), server, token), token)
        } else {
            if (settings.registrationEnabled()){
                view.showSignUpView()
            }

            var hasSocial = false
            if (settings.facebookEnabled()) {
                view.enableLoginByFacebook()
                hasSocial = true
            }
            if (settings.githubEnabled()) {
                view.enableLoginByGithub()
                hasSocial = true
            }
            if (settings.googleEnabled()) {
                view.enableLoginByGoogle()
                hasSocial = true
            }
            if (settings.linkedinEnabled()) {
                view.enableLoginByLinkedin()
                hasSocial = true
            }
            if (settings.meteorEnabled()) {
                view.enableLoginByMeteor()
                hasSocial = true
            }
            if (settings.twitterEnabled()) {
                view.enableLoginByTwitter()
                hasSocial = true
            }
            if (settings.gitlabEnabled()) {
                view.enableLoginByGitlab()
                hasSocial = true
            }

            if (hasSocial) {
                view.showOauthView()
            }
        }
    }

    fun authenticate(usernameOrEmail: String, password: String) {
        val server = serverInteractor.get()
        when {
            server == null -> {
                navigator.toServerScreen()
            }
            usernameOrEmail.isBlank() -> {
                view.alertWrongUsernameOrEmail()
            }
            password.isEmpty() -> {
                view.alertWrongPassword()
            }
            else -> {
                launchUI(strategy) {
                    if (NetworkHelper.hasInternetAccess()) {
                        view.showLoading()

                        try {
                            var token: Token? = null
                            if (usernameOrEmail.isEmailValid()) {
                                token = client.loginWithEmail(usernameOrEmail, password)
                            } else {
                                val settings = settingsInteractor.get(server)
                                if (settings != null) {
                                    token = if (settings.ldapEnabled()) {
                                        client.loginWithLdap(usernameOrEmail, password)
                                    } else {
                                        client.login(usernameOrEmail, password)
                                    }
                                } else {
                                    navigator.toServerScreen()
                                }
                            }

                            if (token != null) {
                                saveToken(server, TokenModel(token.userId, token.authToken), client.me().username)
                                registerPushToken()
                                navigator.toChatList()
                            } else {
                                view.showGenericErrorMessage()
                            }
                        } catch (exception: RocketChatException) {
                            when (exception) {
                                is RocketChatTwoFactorException -> {
                                    navigator.toTwoFA(usernameOrEmail, password)
                                }
                                else -> {
                                    exception.message?.let {
                                        view.showMessage(it)
                                    }.ifNull {
                                        view.showGenericErrorMessage()
                                    }
                                }
                            }
                        } finally {
                            view.hideLoading()
                        }
                    } else {
                        view.showNoInternetConnection()
                    }
                }
            }
        }
    }

    fun authenticateWithCas(casCredential: String) {
        launchUI(strategy) {
            if (NetworkHelper.hasInternetAccess()) {
                view.showLoading()
                try {
                    val server = serverInteractor.get()
                    if (server != null) {
                        val token = client.loginWithCas(casCredential)
                        saveToken(server, TokenModel(token.userId, token.authToken), client.me().username)
                        registerPushToken()
                        navigator.toChatList()
                    } else {
                        navigator.toServerScreen()
                    }
                } catch (exception: RocketChatException) {
                    exception.message?.let {
                        view.showMessage(it)
                    }.ifNull {
                        view.showGenericErrorMessage()
                    }
                } finally {
                    view.hideLoading()
                }
            } else {
                view.showNoInternetConnection()
            }
        }
    }

    private suspend fun saveToken(server: String, tokenModel: TokenModel, username: String?) {
        multiServerRepository.save(server, tokenModel)
        localRepository.save(LocalRepository.USERNAME_KEY, username)
        registerPushToken()
    }

    fun signup() = navigator.toSignUp()

    private suspend fun registerPushToken() {
        localRepository.get(LocalRepository.KEY_PUSH_TOKEN)?.let {
            client.registerPushToken(it)
        }
        // TODO: Schedule push token registering when it comes up null
    }
}