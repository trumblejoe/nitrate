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
import dev.nitrate.tv.ui.details.DetailsScreen
import androidx.navigation.NavController
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import dev.nitrate.tv.ui.home.HomeScreen
import dev.nitrate.tv.ui.login.LoginScreen
import dev.nitrate.tv.ui.player.PlayerScreen
import kotlinx.serialization.Serializable

@Serializable object ConnectRoute
@Serializable data class LoginRoute(val serverUrl: String, val serverName: String)
@Serializable object HomeRoute
@Serializable data class DetailsRoute(val itemId: String, val seasonId: String? = null)
@Serializable data class PlayerRoute(val itemId: String, val fromStart: Boolean = false)

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
                onOpen = nav::openItem,
                onPlay = { item -> nav.navigate(PlayerRoute(item.id.toString())) },
                onSignOut = {
                    nav.navigate(ConnectRoute) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable<DetailsRoute> { entry ->
            val route = entry.toRoute<DetailsRoute>()
            DetailsScreen(
                repository = container.repository,
                itemId = route.itemId,
                seasonId = route.seasonId,
                onPlay = { item, fromStart -> nav.navigate(PlayerRoute(item.id.toString(), fromStart)) },
                onOpen = nav::openItem,
            )
        }
        composable<PlayerRoute> { entry ->
            val route = entry.toRoute<PlayerRoute>()
            PlayerScreen(
                repository = container.repository,
                itemId = route.itemId,
                fromStart = route.fromStart,
                onExit = { nav.popBackStack() },
            )
        }
    }
}

/** Where selecting an item goes. Seasons open their series with that season selected. */
fun NavController.openItem(item: BaseItemDto) {
    val seriesId = item.seriesId
    when {
        item.type == BaseItemKind.SEASON && seriesId != null ->
            navigate(DetailsRoute(seriesId.toString(), seasonId = item.id.toString()))
        else -> navigate(DetailsRoute(item.id.toString()))
    }
}
