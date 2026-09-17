package com.animesh.fitnesstracker.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.EmptyState
import com.animesh.fitnesstracker.ui.components.SectionLabel
import com.animesh.fitnesstracker.ui.theme.MonoNumber
import com.animesh.fitnesstracker.ui.theme.Tokens

@Composable
fun ActivitiesScreen(onBack: () -> Unit, onOpenActivity: (Long) -> Unit) {
    val container = appContainer()
    val vm: ActivitiesViewModel = viewModel { ActivitiesViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        HealthBackHeader("Health · From the watch", "Activities", state.summary.ifEmpty { null }, onBack)
        when {
            state.loading -> Spacer(Modifier.weight(1f))
            state.empty -> EmptyState(
                "Nothing recorded yet",
                "Activities you record on the watch show up here after a sync, with heart rate, zones and the route when there was GPS.",
                Modifier.weight(1f)
            )
            else -> {
                LazyRow(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(ActivityFilter.entries, key = { it.name }) { f ->
                        PillChip(f.label, selected = f == state.filter, onClick = { vm.pick(f) })
                    }
                }
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.groups.isEmpty()) {
                        item("none") { DashedNotice("No ${state.filter.label.lowercase()} recorded on the watch") }
                    }
                    state.groups.forEach { group ->
                        item("month_${group.month}") {
                            Row(
                                Modifier.fillMaxWidth().padding(end = 4.dp, top = 8.dp, bottom = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                SectionLabel(group.month)
                                Text(group.total, style = MonoNumber.copy(fontSize = 12.sp), color = Tokens.Dim)
                            }
                        }
                        items(group.items, key = { "act_${it.id}" }) { a ->
                            ActivityRow(a.kind, a.name, a.meta, a.duration, a.sub, linked = a.linked, onClick = { onOpenActivity(a.id) })
                        }
                    }
                }
            }
        }
    }
}
