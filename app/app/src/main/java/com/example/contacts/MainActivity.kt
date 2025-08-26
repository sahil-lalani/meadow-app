package com.example.contacts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.contacts.ui.theme.ContactsTheme
import androidx.room.Room

class MainActivity : ComponentActivity() {

    private val db by lazy {
        Room.databaseBuilder(
            applicationContext,
            ContactDatabase::class.java,
            "contacts.db"
        ).build()
    }

    private val viewModel by viewModels <ContactViewModel>(
        factoryProducer = {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ContactViewModel(db.dao) as T
                }
            }
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ContactsTheme {
                val state = viewModel.state.collectAsState()
                ContactScreen(state = state.value, onEvent = viewModel::onEvent)
            }
        }
    }
}