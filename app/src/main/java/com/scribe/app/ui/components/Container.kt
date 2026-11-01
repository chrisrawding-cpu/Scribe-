package com.scribe.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.scribe.app.AppContainer
import com.scribe.app.app

@Composable
fun container(): AppContainer = LocalContext.current.app.container
