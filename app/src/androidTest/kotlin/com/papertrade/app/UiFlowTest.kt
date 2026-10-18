package com.papertrade.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI flow against the app's default DEMO provider (synthetic prices; assertions never depend on exact values).
 * NOT yet run: needs an emulator/device.
 */
@RunWith(AndroidJUnit4::class)
class UiFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun fourTabsAndDemoBannerAreShown() {
        rule.onNodeWithTag("demo_banner").assertExists()
        listOf("Explore", "Holdings", "Positions", "Orders").forEach { rule.onNodeWithTag("tab_$it").assertExists() }
        rule.onNodeWithTag("ticker_NIFTY").assertExists(); rule.onNodeWithTag("ticker_SENSEX").assertExists()
    }

    @Test fun indexToOptionChainToContractToSimulatedOrder() {
        rule.onNodeWithTag("ticker_NIFTY").performClick()
        rule.onNodeWithTag("btn_chain").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("chain_table").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("chain_table").onChildren().onFirst().performClick()
        rule.onNodeWithTag("btn_buy").performClick()
        rule.onNodeWithTag("order_ticket").assertExists()
        rule.onNodeWithTag("submit_order").performClick()
    }

    @Test fun emptyOrdersAndPositionsStatesShow() {
        rule.onNodeWithTag("tab_Positions").performClick(); rule.onNodeWithText("No positions").assertExists()
        rule.onNodeWithTag("tab_Orders").performClick()
    }
}
