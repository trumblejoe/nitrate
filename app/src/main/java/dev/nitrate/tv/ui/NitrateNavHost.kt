package dev.nitrate.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.tv.data.AppContainer
import dev.nitrate.tv.ui.connect.ConnectScreen
import dev.nitrate.tv.ui.home.HomeScreen
import dev.nitrate.tv.ui.login.LoginScreen
import dev.nitrate.tv.ui.player.PlayerScreen
import kotlinx.serialization.Serializable

@Serializable object ConnectRoute
@Serializable data class LoginRoute(val serverUrl: String, val serverName: String)
@Serializable object HomeRoute
@Serializable data class PlayerRoute(val itemId: String)

@Composable
fun NitrateNavHost(container: AppContainer) {
    var start by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(Unit) {
        start = if (container.repository.restoreSession()) HomeRoute else ConnectRoute
    }

    val startRoute = start
    if (startRoute == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), Alignment.Center) {
            Text("Nitrate", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = startRoute,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        composable<ConnectRoute> {
            ConnectScreen(
                repository = container.repository,
                onConnected = { url, name -> nav.navigate(LoginRoute(url, name)) },
            )
        }
        composable<LoginRoute> { entry ->
            val route = entry.toRoute<LoginRoute>()
            LoginScreen(
                repository = container.repository,
                serverUrl = route.serverUrl,
                serverName = route.serverName,
                onLoggedIn = {
                    nav.navigate(HomeRoute) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable<HomeRoute> {
            HomeScreen(
                repository = container.repository,
                onPlay = { item -> nav.navigate(PlayerRoute(item.id.toString())) },
                onSignOut = {
                    nav.navigate(ConnectRoute) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable<PlayerRoute> { entry ->
            PlayerScreen(
                repository = container.repository,
                itemId = entry.toRoute<PlayerRoute>().itemId,
                onExit = { nav.popBackStack() },
            )
        }
    }
}
