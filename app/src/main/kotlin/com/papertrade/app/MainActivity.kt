package com.papertrade.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.papertrade.app.data.market.Underlying
import com.papertrade.app.ui.AppVM
import com.papertrade.app.ui.Factory
import com.papertrade.app.ui.screens.*
import com.papertrade.app.ui.theme.PaperTheme

class MainActivity : ComponentActivity() {
    private val hub get() = (application as PaperTradeApp).container.hub
    override fun onStart() { super.onStart(); hub.foreground.value = true }
    override fun onStop() { super.onStop(); hub.foreground.value = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PaperTradeApp).container
        setContent {
            PaperTheme {
                val nav = rememberNavController()
                val vm: AppVM = viewModel(factory = Factory(container))
                val n = remember(nav) {
                    Nav(index = { nav.navigate("index/${it.name}") }, stock = { nav.navigate("stock/${Uri.encode(it)}") }, chain = { nav.navigate("chain/${it.name}") },
                        futures = { nav.navigate("futures/${it.name}") }, contract = { nav.navigate("contract/${Uri.encode(it)}") }, account = { nav.navigate("account") }, back = { nav.popBackStack() }, fyersLogin = { nav.navigate("fyers_login") }, upstoxLogin = { nav.navigate("upstox_login") })
                }
                val sym = navArgument("s") { type = NavType.StringType }; val und = navArgument("u") { type = NavType.StringType }
                NavHost(nav, startDestination = "main") {
                    composable("main") { MainScreen(vm, n) }
                    composable("index/{u}", listOf(und)) { IndexScreen(container, vm, Underlying.valueOf(it.arguments!!.getString("u")!!), n) }
                    composable("chain/{u}", listOf(und)) { ChainScreen(container, vm, Underlying.valueOf(it.arguments!!.getString("u")!!), n) }
                    composable("futures/{u}", listOf(und)) { FuturesScreen(container, vm, Underlying.valueOf(it.arguments!!.getString("u")!!), n) }
                    composable("stock/{s}", listOf(sym)) { StockScreen(container, vm, it.arguments!!.getString("s")!!, n) }
                    composable("contract/{s}", listOf(sym)) { ContractScreen(vm, it.arguments!!.getString("s")!!, n) }
                    composable("account") { AccountScreen(vm, n) }
                    composable("fyers_login") { FyersLoginScreen(vm, n) }
                    composable("upstox_login") { UpstoxLoginScreen(vm, n) }
                }
            }
        }
    }
}
