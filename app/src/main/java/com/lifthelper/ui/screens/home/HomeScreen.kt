package com.lifthelper.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lifthelper.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStartWorkout: () -> Unit,
    onViewHistory: () -> Unit,
    onSettings: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Start Workout Button
            Button(
                onClick = onStartWorkout,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FitnessCenter,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.start_workout),
                    style = MaterialTheme.typography.headlineSmall
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // View History Button
            OutlinedButton(
                onClick = onViewHistory,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.view_history))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Settings Button
            OutlinedButton(
                onClick = onSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.settings))
            }
        }
    }
}
