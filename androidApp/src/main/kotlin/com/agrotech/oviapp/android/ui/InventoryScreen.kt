package com.agrotech.oviapp.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agrotech.oviapp.animals.domain.model.EweInventoryItem
import com.agrotech.oviapp.animals.domain.model.InventoryTab
import com.agrotech.oviapp.animals.domain.model.LambInventoryItem
import com.agrotech.oviapp.animals.domain.model.RamInventoryItem
import com.agrotech.oviapp.animals.domain.repository.AnimalInventoryRepository
import com.agrotech.oviapp.domain.model.LifeStage
import com.agrotech.oviapp.domain.model.ReproductiveStatus
import com.agrotech.oviapp.domain.model.Sex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val BaappPrimaryGreen = Color(0xFF1B5E20)
private val BaappBackground = Color(0xFFF7F9F7)
private val BaappSoftGreen = Color(0xFFE8F5E9)
private val BaappFemalePink = Color(0xFFD81B60)
private val BaappFemalePinkBg = Color(0xFFFCE4EC)
private val BaappMaleBlue = Color(0xFF1565C0)
private val BaappMaleBlueBg = Color(0xFFE3F2FD)

private data class PaginationTag(
    val tab: InventoryTab,
    val query: String,
    val reproductiveStatus: ReproductiveStatus?,
    val lifeStage: LifeStage?
) {
    fun matches(
        currentTab: InventoryTab,
        currentQuery: String,
        currentReproductiveStatus: ReproductiveStatus?,
        currentLifeStage: LifeStage?
    ): Boolean = tab == currentTab &&
        query == currentQuery &&
        reproductiveStatus == currentReproductiveStatus &&
        lifeStage == currentLifeStage
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InventoryScreen(
    ranchId: String,
    inventoryRepository: AnimalInventoryRepository,
    onBackClick: () -> Unit,
    onEditAnimalClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = remember { InventoryTab.entries }
    val currentTab = tabs[selectedTabIndex]

    var searchQuery by remember { mutableStateOf("") }
    var selectedReproductiveStatus by remember { mutableStateOf<ReproductiveStatus?>(null) }
    var selectedLifeStage by remember { mutableStateOf<LifeStage?>(null) }

    var ewesList by remember { mutableStateOf<List<EweInventoryItem>>(emptyList()) }
    var ramsList by remember { mutableStateOf<List<RamInventoryItem>>(emptyList()) }
    var lambsList by remember { mutableStateOf<List<LambInventoryItem>>(emptyList()) }

    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var initialRetryTrigger by remember { mutableIntStateOf(0) }
    var paginationRetryTrigger by remember { mutableIntStateOf(0) }
    var canLoadMore by remember { mutableStateOf(true) }
    var currentOffset by remember { mutableStateOf(0L) }
    val pageSize = 50L

    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    // Reload list when tab, search, or filters change (or initial load retry)
    LaunchedEffect(currentTab, searchQuery, selectedReproductiveStatus, selectedLifeStage, initialRetryTrigger) {
        isLoading = true
        loadError = null
        currentOffset = 0L
        canLoadMore = true
        try {
            when (currentTab) {
                InventoryTab.EWES -> {
                    val results = inventoryRepository.getEwesPaged(
                        ranchId = ranchId,
                        query = searchQuery,
                        reproductiveStatus = selectedReproductiveStatus,
                        limit = pageSize,
                        offset = 0L
                    )
                    ewesList = results
                    canLoadMore = results.size.toLong() == pageSize
                }
                InventoryTab.RAMS -> {
                    val results = inventoryRepository.getRamsPaged(
                        ranchId = ranchId,
                        query = searchQuery,
                        limit = pageSize,
                        offset = 0L
                    )
                    ramsList = results
                    canLoadMore = results.size.toLong() == pageSize
                }
                InventoryTab.LAMBS -> {
                    val results = inventoryRepository.getLambsPaged(
                        ranchId = ranchId,
                        query = searchQuery,
                        lifeStage = selectedLifeStage,
                        limit = pageSize,
                        offset = 0L
                    )
                    lambsList = results
                    canLoadMore = results.size.toLong() == pageSize
                }
            }
            currentOffset = pageSize
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.message ?: "No se pudo cargar el inventario."
        } finally {
            isLoading = false
        }
    }

    // Infinite scroll listener
    LaunchedEffect(listState, currentTab, searchQuery, selectedReproductiveStatus, selectedLifeStage, paginationRetryTrigger) {
        val requestTag = PaginationTag(currentTab, searchQuery, selectedReproductiveStatus, selectedLifeStage)
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val nearBottom = lastVisible >= totalItems - 5 && totalItems > 0
            nearBottom && canLoadMore && !isLoading && loadError == null
        }.distinctUntilChanged().collect { shouldLoadMore ->
            if (shouldLoadMore && canLoadMore && !isLoading && loadError == null) {
                isLoading = true
                loadError = null
                val fetchOffset = currentOffset
                try {
                    when (currentTab) {
                        InventoryTab.EWES -> {
                            val more = inventoryRepository.getEwesPaged(
                                ranchId = ranchId,
                                query = searchQuery,
                                reproductiveStatus = selectedReproductiveStatus,
                                limit = pageSize,
                                offset = fetchOffset
                            )
                            if (requestTag.matches(currentTab, searchQuery, selectedReproductiveStatus, selectedLifeStage)) {
                                ewesList = ewesList + more
                                canLoadMore = more.size.toLong() == pageSize
                                currentOffset = fetchOffset + pageSize
                            }
                        }
                        InventoryTab.RAMS -> {
                            val more = inventoryRepository.getRamsPaged(
                                ranchId = ranchId,
                                query = searchQuery,
                                limit = pageSize,
                                offset = fetchOffset
                            )
                            if (requestTag.matches(currentTab, searchQuery, selectedReproductiveStatus, selectedLifeStage)) {
                                ramsList = ramsList + more
                                canLoadMore = more.size.toLong() == pageSize
                                currentOffset = fetchOffset + pageSize
                            }
                        }
                        InventoryTab.LAMBS -> {
                            val more = inventoryRepository.getLambsPaged(
                                ranchId = ranchId,
                                query = searchQuery,
                                lifeStage = selectedLifeStage,
                                limit = pageSize,
                                offset = fetchOffset
                            )
                            if (requestTag.matches(currentTab, searchQuery, selectedReproductiveStatus, selectedLifeStage)) {
                                lambsList = lambsList + more
                                canLoadMore = more.size.toLong() == pageSize
                                currentOffset = fetchOffset + pageSize
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    loadError = e.message ?: "Error al cargar más registros."
                } finally {
                    isLoading = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Inventario del Rebaño",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    androidx.compose.material3.TextButton(onClick = onBackClick) {
                        Text("← Volver", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BaappPrimaryGreen,
                    titleContentColor = Color.White
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BaappBackground)
                .padding(innerPadding)
        ) {
            // TabRow with exactly 3 tabs: Hembras, Sementales, Crías (strictly Sementales terminology)
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = Color.White,
                contentColor = BaappPrimaryGreen,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                        color = BaappPrimaryGreen
                    )
                }
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = {
                            selectedTabIndex = index
                            selectedReproductiveStatus = null
                            selectedLifeStage = null
                        },
                        text = {
                            Text(
                                text = tab.title,
                                fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }

            // Search Bar (Folio / Arete or SINIIGA chip)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Buscar por arete interno o chip SINIIGA...") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                // Tab-specific Filter Chips
                if (currentTab == InventoryTab.EWES) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = selectedReproductiveStatus == null,
                            onClick = { selectedReproductiveStatus = null },
                            label = { Text("Todas") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BaappSoftGreen)
                        )
                        FilterChip(
                            selected = selectedReproductiveStatus == ReproductiveStatus.EMPTY,
                            onClick = { selectedReproductiveStatus = ReproductiveStatus.EMPTY },
                            label = { Text("Vacías") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BaappSoftGreen)
                        )
                        FilterChip(
                            selected = selectedReproductiveStatus == ReproductiveStatus.PREGNANT,
                            onClick = { selectedReproductiveStatus = ReproductiveStatus.PREGNANT },
                            label = { Text("Gestantes") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BaappSoftGreen)
                        )
                        FilterChip(
                            selected = selectedReproductiveStatus == ReproductiveStatus.LACTATING,
                            onClick = { selectedReproductiveStatus = ReproductiveStatus.LACTATING },
                            label = { Text("Lactando") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BaappSoftGreen)
                        )
                    }
                } else if (currentTab == InventoryTab.LAMBS) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = selectedLifeStage == null,
                            onClick = { selectedLifeStage = null },
                            label = { Text("Todas") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BaappSoftGreen)
                        )
                        FilterChip(
                            selected = selectedLifeStage == LifeStage.LAMB_LACTATING,
                            onClick = { selectedLifeStage = LifeStage.LAMB_LACTATING },
                            label = { Text("Lactancia") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BaappSoftGreen)
                        )
                        FilterChip(
                            selected = selectedLifeStage == LifeStage.LAMB_FATTENING,
                            onClick = { selectedLifeStage = LifeStage.LAMB_FATTENING },
                            label = { Text("Engorda") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BaappSoftGreen)
                        )
                        FilterChip(
                            selected = selectedLifeStage == LifeStage.EWE_REPLACEMENT,
                            onClick = { selectedLifeStage = LifeStage.EWE_REPLACEMENT },
                            label = { Text("Reemplazo") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BaappSoftGreen)
                        )
                    }
                }
            }

            // LazyColumn (Fluid scroll with 1,000+ records)
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when (currentTab) {
                        InventoryTab.EWES -> {
                            if (ewesList.isEmpty() && !isLoading && loadError == null) {
                                item { EmptyInventoryView("No se encontraron hembras activas.") }
                            } else {
                                items(ewesList, key = { it.id }) { ewe ->
                                    EweCard(
                                        ewe = ewe,
                                        onEditClick = onEditAnimalClick?.let { handler -> { handler(ewe.id) } }
                                    )
                                }
                            }
                        }
                        InventoryTab.RAMS -> {
                            if (ramsList.isEmpty() && !isLoading && loadError == null) {
                                item { EmptyInventoryView("No se encontraron sementales activos.") }
                            } else {
                                items(ramsList, key = { it.id }) { ram ->
                                    RamCard(
                                        ram = ram,
                                        onEditClick = onEditAnimalClick?.let { handler -> { handler(ram.id) } }
                                    )
                                }
                            }
                        }
                        InventoryTab.LAMBS -> {
                            if (lambsList.isEmpty() && !isLoading && loadError == null) {
                                item { EmptyInventoryView("No se encontraron crías activas.") }
                            } else {
                                items(lambsList, key = { it.id }) { lamb ->
                                    LambCard(
                                        lamb = lamb,
                                        onEditClick = onEditAnimalClick?.let { handler -> { handler(lamb.id) } }
                                    )
                                }
                            }
                        }
                    }

                    if (loadError != null) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = loadError!!,
                                        color = Color(0xFFB71C1C),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedButton(
                                        onClick = {
                                            loadError = null
                                            if (currentOffset == 0L) {
                                                initialRetryTrigger++
                                            } else {
                                                paginationRetryTrigger++
                                            }
                                        },
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    ) {
                                        Text("Reintentar")
                                    }
                                }
                            }
                        }
                    }

                    if (isLoading) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = BaappPrimaryGreen, strokeWidth = 3.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EweCard(ewe: EweInventoryItem, onEditClick: (() -> Unit)?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "♀ Hembra",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = BaappFemalePink,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier
                            .background(BaappFemalePinkBg, shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Arete: ${ewe.folioInterno}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                val (statusText, statusBg, statusColor) = when (ewe.reproductiveStatus) {
                    ReproductiveStatus.EMPTY -> Triple("Vacía", Color(0xFFECEFF1), Color(0xFF455A64))
                    ReproductiveStatus.PREGNANT -> Triple("Gestante", Color(0xFFFFF3E0), Color(0xFFE65100))
                    ReproductiveStatus.LACTATING -> Triple("Lactando", Color(0xFFE8F5E9), Color(0xFF2E7D32))
                    else -> Triple(ewe.reproductiveStatus.name, Color(0xFFECEFF1), Color.Gray)
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall.copy(color = statusColor, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .background(statusBg, shape = RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Raza: ${ewe.breed} · Color: ${ewe.color}",
                style = MaterialTheme.typography.bodySmall.copy(color = Color.DarkGray)
            )

            if (!ewe.siniigaChip.isNullOrBlank()) {
                Text(
                    text = "SINIIGA: ${ewe.siniigaChip}",
                    style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray)
                )
            }

            if (onEditClick != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onEditClick,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Editar Ficha", color = BaappPrimaryGreen, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun RamCard(ram: RamInventoryItem, onEditClick: (() -> Unit)?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "♂ Semental",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = BaappMaleBlue,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier
                            .background(BaappMaleBlueBg, shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Arete: ${ram.folioInterno}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                val scrotalText = ram.latestScrotalCircumferenceCm?.let { "CE: ${it} cm" } ?: "CE: Sin registro"
                Text(
                    text = scrotalText,
                    style = MaterialTheme.typography.labelSmall.copy(color = BaappMaleBlue, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .background(BaappMaleBlueBg, shape = RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Raza: ${ram.breed} · Color: ${ram.color}",
                style = MaterialTheme.typography.bodySmall.copy(color = Color.DarkGray)
            )

            if (!ram.siniigaChip.isNullOrBlank()) {
                Text(
                    text = "SINIIGA: ${ram.siniigaChip}",
                    style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Crías engendradas:",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium, color = Color.DarkGray)
                )
                Text(
                    text = "${ram.activeOffspringCount} activas (${ram.totalOffspringCount} total)",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = BaappPrimaryGreen)
                )
            }

            if (onEditClick != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onEditClick,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Editar Ficha", color = BaappPrimaryGreen, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun LambCard(lamb: LambInventoryItem, onEditClick: (() -> Unit)?) {
    val isMale = lamb.sex == Sex.MALE
    val sexLabel = if (isMale) "♂ Macho" else "♀ Hembra"
    val sexColor = if (isMale) BaappMaleBlue else BaappFemalePink
    val sexBg = if (isMale) BaappMaleBlueBg else BaappFemalePinkBg

    val stageLabel = when (lamb.lifeStage) {
        LifeStage.LAMB_LACTATING -> "Lactando"
        LifeStage.LAMB_FATTENING -> "Engorda"
        LifeStage.EWE_REPLACEMENT -> "Recría / Reemplazo"
        else -> lamb.lifeStage.name
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = sexLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = sexColor,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier
                            .background(sexBg, shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Arete: ${lamb.folioInterno}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Text(
                    text = stageLabel,
                    style = MaterialTheme.typography.labelSmall.copy(color = BaappPrimaryGreen, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .background(BaappSoftGreen, shape = RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Raza: ${lamb.breed} · Color: ${lamb.color}",
                style = MaterialTheme.typography.bodySmall.copy(color = Color.DarkGray)
            )

            if (!lamb.siniigaChip.isNullOrBlank()) {
                Text(
                    text = "SINIIGA: ${lamb.siniigaChip}",
                    style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray)
                )
            }

            if (onEditClick != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onEditClick,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Editar Ficha", color = BaappPrimaryGreen, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyInventoryView(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium.copy(color = Color.Gray)
        )
    }
}
