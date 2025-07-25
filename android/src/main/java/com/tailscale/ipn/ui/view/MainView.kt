// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.ShowHide
import com.tailscale.ipn.ui.Links
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Permissions
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.theme.customErrorContainer
import com.tailscale.ipn.ui.theme.disabled
import com.tailscale.ipn.ui.theme.errorButton
import com.tailscale.ipn.ui.theme.errorListItem
import com.tailscale.ipn.ui.theme.exitNodeToggleButton
import com.tailscale.ipn.ui.theme.listItem
import com.tailscale.ipn.ui.theme.minTextSize
import com.tailscale.ipn.ui.theme.primaryListItem
import com.tailscale.ipn.ui.theme.searchBarColors
import com.tailscale.ipn.ui.theme.secondaryButton
import com.tailscale.ipn.ui.theme.short
import com.tailscale.ipn.ui.theme.surfaceContainerListItem
import com.tailscale.ipn.ui.theme.warningButton
import com.tailscale.ipn.ui.theme.warningListItem
import com.tailscale.ipn.ui.util.AndroidTVUtil.isAndroidTV
import com.tailscale.ipn.ui.util.AutoResizingText
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.PeerSet
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.viewModel.IpnViewModel.NodeState
import com.tailscale.ipn.ui.viewModel.MainViewModel
import com.tailscale.ipn.ui.viewModel.VpnViewModel
import com.tailscale.ipn.util.FeatureFlags

// Navigation actions for the MainView
data class MainViewNavigation(
    val onNavigateToSettings: () -> Unit,
    val onNavigateToPeerDetails: (Tailcfg.Node) -> Unit,
    val onNavigateToExitNodes: () -> Unit,
    val onNavigateToHealth: () -> Unit,
    val onNavigateToSearch: () -> Unit,
)

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainView(
    loginAtUrl: (String) -> Unit,
    navigation: MainViewNavigation,
    viewModel: MainViewModel,
) {
  val currentPingDevice by viewModel.pingViewModel.peer.collectAsState()
  val healthIcon by viewModel.healthIcon.collectAsState()

  LoadingIndicator.Wrap {
    Scaffold(contentWindowInsets = WindowInsets.Companion.statusBars) { paddingInsets ->
      Column(
          modifier = Modifier.fillMaxWidth().padding(paddingInsets),
          verticalArrangement = Arrangement.Center) {
            // Assume VPN has been prepared for optimistic UI. Whether or not it has been prepared
            // cannot be known
            // until permission has been granted to prepare the VPN.
            val isPrepared by viewModel.isVpnPrepared.collectAsState(initial = true)
            val isOn by viewModel.vpnToggleState.collectAsState(initial = false)
            val state by viewModel.ipnState.collectAsState(initial = Ipn.State.NoState)
            val user by viewModel.loggedInUser.collectAsState(initial = null)
            val stateVal by viewModel.stateRes.collectAsState(initial = R.string.placeholder)
            val stateStr = stringResource(id = stateVal)
            val netmap by viewModel.netmap.collectAsState(initial = null)
            val showExitNodePicker by MDMSettings.exitNodesPicker.flow.collectAsState()
            val disableToggle by MDMSettings.forceEnabled.flow.collectAsState()
            val showKeyExpiry by viewModel.showExpiry.collectAsState(initial = false)
            val showDirectoryPickerInterstitial by
                viewModel.showDirectoryPickerInterstitial.collectAsState()

            // Hide the header only on Android TV when the user needs to login
            val hideHeader = (isAndroidTV() && state == Ipn.State.NeedsLogin)

            ListItem(
                colors = MaterialTheme.colorScheme.surfaceContainerListItem,
                leadingContent = {
                  if (!hideHeader) {
                    TintedSwitch(
                        checked = isOn,
                        enabled =
                            !disableToggle.value &&
                                !viewModel.isToggleInProgress
                                    .value, // Disable switch if toggle is in progress
                        onCheckedChange = { desiredState -> viewModel.toggleVpn(desiredState) })
                  }
                },
                headlineContent = {
                  user?.NetworkProfile?.DomainName?.let { domain ->
                    AutoResizingText(
                        text = domain,
                        style = MaterialTheme.typography.titleMedium.short,
                        minFontSize = MaterialTheme.typography.minTextSize,
                        overflow = TextOverflow.Ellipsis)
                  }
                },
                supportingContent = {
                  if (!hideHeader) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      Text(text = stateStr, style = MaterialTheme.typography.bodyMedium.short)
                      healthIcon?.let {
                        Spacer(modifier = Modifier.size(4.dp))
                        IconButton(
                            onClick = { navigation.onNavigateToHealth() },
                            modifier = Modifier.size(16.dp)) {
                              Icon(
                                  painterResource(id = it),
                                  contentDescription = null,
                                  modifier = Modifier.size(16.dp),
                                  tint = MaterialTheme.colorScheme.error)
                            }
                      }
                    }
                  }
                },
                trailingContent = {
                  Box(modifier = Modifier.padding(8.dp), contentAlignment = Alignment.CenterEnd) {
                    when (user) {
                      null -> SettingsButton { navigation.onNavigateToSettings() }
                      else -> {
                        Avatar(
                            profile = user,
                            size = 36,
                            { navigation.onNavigateToSettings() },
                            isFocusable = true)
                      }
                    }
                  }
                })

            when (state) {
              Ipn.State.Running -> {
                viewModel.maybeRequestVpnPermission()
                LaunchVpnPermissionIfNeeded(viewModel)
                PromptForMissingPermissions(viewModel)

                if (!viewModel.skipPromptsForAuthKeyLogin()) {
                  LaunchedEffect(state) {
                    if (state == Ipn.State.Running && !isAndroidTV()) {
                      viewModel.checkIfTaildropDirectorySelected()
                    }
                  }
                }

                if (showKeyExpiry) {
                  ExpiryNotification(netmap = netmap, action = { viewModel.login() })
                }

                if (showExitNodePicker.value == ShowHide.Show) {
                  ExitNodeStatus(
                      navAction = navigation.onNavigateToExitNodes, viewModel = viewModel)
                }

                PeerList(
                    viewModel = viewModel,
                    onNavigateToPeerDetails = navigation.onNavigateToPeerDetails,
                    onSearchBarClick = navigation.onNavigateToSearch,
                    onSearch = { viewModel.searchPeers(it) })
              }
              Ipn.State.NoState,
              Ipn.State.Starting -> StartingView()
              else -> {
                ConnectView(
                    state,
                    isPrepared,
                    // If Tailscale is stopping, don't automatically restart; wait for user to take
                    // action (eg, if the user connected to another VPN).
                    state != Ipn.State.Stopping,
                    user,
                    { viewModel.toggleVpn(desiredState = !isOn) },
                    { viewModel.login() },
                    loginAtUrl,
                    netmap?.SelfNode,
                    { viewModel.showVPNPermissionLauncherIfUnauthorized() })
              }
            }

            showDirectoryPickerInterstitial.let { show ->
              if (show) {
                AppTheme {
                  AlertDialog(
                      onDismissRequest = { viewModel.showDirectoryPickerLauncher() },
                      title = {
                        Text(text = stringResource(id = R.string.taildrop_directory_picker_title))
                      },
                      text = { TaildropDirectoryPickerPrompt() },
                      confirmButton = {
                        PrimaryActionButton(onClick = { viewModel.showDirectoryPickerLauncher() }) {
                          Text(
                              text = stringResource(id = R.string.taildrop_directory_picker_button))
                        }
                      })
                }
              }
            }
          }
      currentPingDevice?.let { _ ->
        ModalBottomSheet(onDismissRequest = { viewModel.onPingDismissal() }) {
          PingView(model = viewModel.pingViewModel)
        }
      }
    }
  }
}

@Composable
fun TaildropDirectoryPickerPrompt() {
  val uriHandler = LocalUriHandler.current

  Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.Start) {
    Text(text = stringResource(id = R.string.taildrop_directory_picker_body))
    Text(
        text = stringResource(id = R.string.taildrop_directory_picker_info),
        modifier = Modifier.clickable { uriHandler.openUri(Links.TAILDROP_KB_URL) },
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline)
  }
}

@Composable
fun LaunchVpnPermissionIfNeeded(viewModel: MainViewModel) {
  val lifecycleOwner = LocalLifecycleOwner.current
  val shouldRequest by viewModel.requestVpnPermission.collectAsState()

  LaunchedEffect(shouldRequest) {
    if (!shouldRequest) return@LaunchedEffect

    // Defer showing permission launcher until activity is resumed to avoid silent RESULT_CANCELED
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      viewModel.showVPNPermissionLauncherIfUnauthorized()
    }
  }
}

@Composable
fun ExitNodeStatus(navAction: () -> Unit, viewModel: MainViewModel) {
  val nodeState by viewModel.nodeState.collectAsState()
  val maybePrefs by viewModel.prefs.collectAsState()
  val netmap by viewModel.netmap.collectAsState()

  // There's nothing to render if we haven't loaded the prefs yet
  val prefs = maybePrefs ?: return

  // The activeExitNode is the source of truth.  The selectedExitNode is only relevant if we
  // don't have an active node.
  val chosenExitNodeId = prefs.activeExitNodeID ?: prefs.selectedExitNodeID

  val exitNodePeer = chosenExitNodeId?.let { id -> netmap?.Peers?.find { it.StableID == id } }
  val name = exitNodePeer?.exitNodeName

  val managedByOrganization by viewModel.managedByOrganization.collectAsState()

  Box(
      modifier =
          Modifier.fillMaxWidth().background(color = MaterialTheme.colorScheme.surfaceContainer)) {
        if (nodeState == NodeState.OFFLINE_MDM) {
          Box(
              modifier =
                  Modifier.padding(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 16.dp)
                      .clip(shape = RoundedCornerShape(10.dp, 10.dp, 10.dp, 10.dp))
                      .background(MaterialTheme.colorScheme.customErrorContainer)
                      .fillMaxWidth()
                      .align(Alignment.TopCenter)) {
                Column(
                    modifier =
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 36.dp, bottom = 16.dp)) {
                      Text(
                          text =
                              managedByOrganization.value?.let {
                                stringResource(R.string.exit_node_offline_mdm_orgname, it)
                              } ?: stringResource(R.string.exit_node_offline_mdm),
                          style = MaterialTheme.typography.bodyMedium,
                          color = Color.White)
                    }
              }
        }

        Box(
            modifier =
                Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
                    .clip(shape = RoundedCornerShape(10.dp, 10.dp, 10.dp, 10.dp))
                    .fillMaxWidth()) {
              ListItem(
                  modifier = Modifier.clickable { navAction() },
                  colors =
                      when (nodeState) {
                        NodeState.ACTIVE_AND_RUNNING -> MaterialTheme.colorScheme.primaryListItem
                        NodeState.ACTIVE_NOT_RUNNING -> MaterialTheme.colorScheme.listItem
                        NodeState.RUNNING_AS_EXIT_NODE -> MaterialTheme.colorScheme.warningListItem
                        NodeState.OFFLINE_ENABLED -> MaterialTheme.colorScheme.errorListItem
                        NodeState.OFFLINE_DISABLED -> MaterialTheme.colorScheme.errorListItem
                        NodeState.OFFLINE_MDM -> MaterialTheme.colorScheme.errorListItem
                        else ->
                            ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surface)
                      },
                  overlineContent = {
                    Text(
                        text =
                            if (nodeState == NodeState.OFFLINE_ENABLED ||
                                nodeState == NodeState.OFFLINE_DISABLED ||
                                nodeState == NodeState.OFFLINE_MDM)
                                stringResource(R.string.exit_node_offline)
                            else stringResource(R.string.exit_node),
                        style = MaterialTheme.typography.bodySmall,
                    )
                  },
                  headlineContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      Text(
                          text =
                              when (nodeState) {
                                NodeState.NONE -> stringResource(id = R.string.none)
                                NodeState.RUNNING_AS_EXIT_NODE ->
                                    stringResource(id = R.string.running_exit_node)
                                else -> name ?: ""
                              },
                          style = MaterialTheme.typography.bodyMedium,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis)
                      Icon(
                          imageVector = Icons.Outlined.ArrowDropDown,
                          contentDescription = null,
                          tint =
                              if (nodeState == NodeState.NONE)
                                  MaterialTheme.colorScheme.onSurfaceVariant
                              else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                      )
                    }
                  },
                  trailingContent = {
                    if (nodeState != NodeState.NONE) {
                      Button(
                          colors =
                              when (nodeState) {
                                NodeState.OFFLINE_ENABLED -> MaterialTheme.colorScheme.errorButton
                                NodeState.OFFLINE_DISABLED -> MaterialTheme.colorScheme.errorButton
                                NodeState.OFFLINE_MDM -> MaterialTheme.colorScheme.errorButton
                                NodeState.RUNNING_AS_EXIT_NODE ->
                                    MaterialTheme.colorScheme.warningButton
                                NodeState.ACTIVE_NOT_RUNNING ->
                                    MaterialTheme.colorScheme.exitNodeToggleButton
                                else -> MaterialTheme.colorScheme.secondaryButton
                              },
                          onClick = {
                            if (nodeState == NodeState.RUNNING_AS_EXIT_NODE)
                                viewModel.setRunningExitNode(false)
                            else viewModel.toggleExitNode()
                          }) {
                            Text(
                                when (nodeState) {
                                  NodeState.OFFLINE_DISABLED -> stringResource(id = R.string.enable)
                                  NodeState.ACTIVE_NOT_RUNNING ->
                                      stringResource(id = R.string.enable)
                                  NodeState.RUNNING_AS_EXIT_NODE ->
                                      stringResource(id = R.string.stop)
                                  else -> stringResource(id = R.string.disable)
                                })
                          }
                    }
                  })
            }
      }
}

@Composable
fun SettingsButton(action: () -> Unit) {
  IconButton(modifier = Modifier.size(24.dp), onClick = { action() }) {
    Icon(
        Icons.Outlined.Settings,
        contentDescription = "Open settings",
        tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
fun StartingView() {
  Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally) {
        TailscaleLogoView(
            animated = true, usesOnBackgroundColors = false, Modifier.size(40.dp).alpha(0.3f))
      }
}

@Composable
fun ConnectView(
    state: Ipn.State,
    isPrepared: Boolean,
    shouldStartAutomatically: Boolean,
    user: IpnLocal.LoginProfile?,
    connectAction: () -> Unit,
    loginAction: () -> Unit,
    loginAtUrlAction: (String) -> Unit,
    selfNode: Tailcfg.Node?,
    showVPNPermissionLauncher: () -> Unit,
) {
  LaunchedEffect(isPrepared) {
    if (!isPrepared && shouldStartAutomatically) {
      showVPNPermissionLauncher()
    }
  }
  Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
      Column(
          modifier = Modifier.padding(8.dp).fillMaxWidth(0.7f).fillMaxHeight(),
          verticalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.CenterVertically),
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        if (!isPrepared) {
          TailscaleLogoView(modifier = Modifier.size(50.dp))
          Spacer(modifier = Modifier.size(1.dp))
          Text(
              text = stringResource(id = R.string.welcome_to_tailscale),
              style = MaterialTheme.typography.titleMedium,
              textAlign = TextAlign.Center)
          Text(
              stringResource(R.string.give_permissions),
              style = MaterialTheme.typography.titleSmall,
              textAlign = TextAlign.Center)
          Spacer(modifier = Modifier.size(1.dp))
          PrimaryActionButton(onClick = connectAction) {
            Text(
                text = stringResource(id = R.string.connect),
                fontSize = MaterialTheme.typography.titleMedium.fontSize)
          }
        } else if (state == Ipn.State.NeedsMachineAuth) {
          Icon(
              modifier = Modifier.size(40.dp),
              imageVector = Icons.Outlined.Lock,
              contentDescription = "Device requires authentication")
          Text(
              text = stringResource(id = R.string.machine_auth_required),
              style = MaterialTheme.typography.titleMedium,
              textAlign = TextAlign.Center)
          Text(
              text = stringResource(id = R.string.machine_auth_explainer),
              style = MaterialTheme.typography.bodyMedium,
              textAlign = TextAlign.Center)
          Spacer(modifier = Modifier.size(1.dp))
          selfNode?.let {
            PrimaryActionButton(onClick = { loginAtUrlAction(it.nodeAdminUrl) }) {
              Text(
                  text = stringResource(id = R.string.open_admin_console),
                  fontSize = MaterialTheme.typography.titleMedium.fontSize)
            }
          }
        } else if (state != Ipn.State.NeedsLogin && user != null && !user.isEmpty()) {
          Icon(
              painter = painterResource(id = R.drawable.power),
              contentDescription = null,
              modifier = Modifier.size(40.dp),
              tint = MaterialTheme.colorScheme.disabled)
          Text(
              text = stringResource(id = R.string.not_connected),
              fontSize = MaterialTheme.typography.titleMedium.fontSize,
              fontWeight = FontWeight.SemiBold,
              textAlign = TextAlign.Center,
              fontFamily = MaterialTheme.typography.titleMedium.fontFamily)
          val tailnetName = user.NetworkProfile?.DomainName ?: ""
          Text(
              buildAnnotatedString {
                append(stringResource(id = R.string.connect_to_tailnet_prefix))
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(tailnetName)
                pop()
                append(stringResource(id = R.string.connect_to_tailnet_suffix))
              },
              fontSize = MaterialTheme.typography.titleMedium.fontSize,
              fontWeight = FontWeight.Normal,
              textAlign = TextAlign.Center,
          )
          Spacer(modifier = Modifier.size(1.dp))
          PrimaryActionButton(onClick = connectAction) {
            Text(
                text = stringResource(id = R.string.connect),
                fontSize = MaterialTheme.typography.titleMedium.fontSize)
          }
        } else {
          TailscaleLogoView(modifier = Modifier.size(50.dp))
          Spacer(modifier = Modifier.size(1.dp))
          Text(
              text = stringResource(id = R.string.welcome_to_tailscale),
              style = MaterialTheme.typography.titleMedium,
              textAlign = TextAlign.Center)
          Text(
              stringResource(R.string.login_to_join_your_tailnet),
              style = MaterialTheme.typography.titleSmall,
              textAlign = TextAlign.Center)
          Spacer(modifier = Modifier.size(1.dp))
          PrimaryActionButton(onClick = loginAction) {
            Text(
                text = stringResource(id = R.string.log_in),
                fontSize = MaterialTheme.typography.titleMedium.fontSize)
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PeerList(
    viewModel: MainViewModel,
    onNavigateToPeerDetails: (Tailcfg.Node) -> Unit,
    onSearchBarClick: () -> Unit,
    onSearch: (String) -> Unit,
) {
  val peerList by viewModel.peers.collectAsState(initial = emptyList<PeerSet>())
  val searchTermStr by viewModel.searchTerm.collectAsState(initial = "")
  val showNoResults =
      remember { derivedStateOf { searchTermStr.isNotEmpty() && peerList.isEmpty() } }.value

  val netmap = viewModel.netmap.collectAsState()
  val focusManager = LocalFocusManager.current
  var isSearchFocussed by remember { mutableStateOf(false) }
  var isListFocussed by remember { mutableStateOf(false) }
  val expandedPeer = viewModel.expandedMenuPeer.collectAsState()
  val localClipboardManager = LocalClipboardManager.current
  // Restrict search to devices running API 33+ (see https://github.com/tailscale/corp/issues/27375)
  val enableSearch = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

  Column(modifier = Modifier.fillMaxSize()) {
    if (enableSearch && FeatureFlags.isEnabled("enable_new_search")) {
      Search(onSearchBarClick)
    } else {
      if (!isAndroidTV()) {
        Box(
            modifier =
                Modifier.fillMaxWidth().background(color = MaterialTheme.colorScheme.surface)) {
              OutlinedTextField(
                  modifier =
                      Modifier.fillMaxWidth()
                          .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 0.dp)
                          .onFocusChanged { isSearchFocussed = it.isFocused },
                  singleLine = true,
                  shape = MaterialTheme.shapes.extraLarge,
                  colors = MaterialTheme.colorScheme.searchBarColors,
                  leadingIcon = {
                    Icon(imageVector = Icons.Outlined.Search, contentDescription = "search")
                  },
                  trailingIcon = {
                    if (isSearchFocussed) {
                      IconButton(
                          onClick = {
                            focusManager.clearFocus()
                            onSearch("")
                          }) {
                            Icon(
                                imageVector =
                                    if (searchTermStr.isEmpty()) Icons.Outlined.Close
                                    else Icons.Outlined.Clear,
                                contentDescription = "clear search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                          }
                    }
                  },
                  placeholder = {
                    Text(
                        text = stringResource(id = R.string.search),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1)
                  },
                  value = searchTermStr,
                  onValueChange = { onSearch(it) })
            }
      }
    }

    // Peers display
    LazyColumn(
        modifier =
            Modifier.fillMaxWidth()
                .weight(1f) // LazyColumn gets the remaining vertical space
                .onFocusChanged { isListFocussed = it.isFocused }
                .background(color = MaterialTheme.colorScheme.surface)) {

          // Handle case when no results are found
          if (showNoResults) {
            item {
              Spacer(
                  Modifier.height(16.dp)
                      .fillMaxSize()
                      .focusable(false)
                      .background(color = MaterialTheme.colorScheme.surface))
              Lists.LargeTitle(
                  stringResource(id = R.string.no_results),
                  bottomPadding = 8.dp,
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.Light)
            }
          }

          // Iterate over peer sets to display them
          var first = true
          peerList.forEach { peerSet ->
            if (!first) {
              item(key = "user_divider_${peerSet.user?.ID ?: 0L}") { Lists.ItemDivider() }
            }
            first = false

            if (isAndroidTV()) {
              item { NodesSectionHeader(peerSet = peerSet) }
            } else {
              stickyHeader { NodesSectionHeader(peerSet = peerSet) }
            }

            itemsWithDividers(peerSet.peers, key = { it.StableID }) { peer ->
              ListItem(
                  modifier =
                      Modifier.combinedClickable(
                          onClick = { onNavigateToPeerDetails(peer) },
                          onLongClick = { viewModel.expandedMenuPeer.set(peer) }),
                  colors = MaterialTheme.colorScheme.listItem,
                  headlineContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      Box(
                          modifier =
                              Modifier.padding(top = 2.dp)
                                  .size(10.dp)
                                  .background(
                                      color = peer.connectedColor(netmap.value),
                                      shape = RoundedCornerShape(percent = 50))) {}
                      Spacer(modifier = Modifier.size(8.dp))
                      Text(text = peer.displayName, style = MaterialTheme.typography.titleMedium)
                      DropdownMenu(
                          expanded = expandedPeer.value?.StableID == peer.StableID,
                          onDismissRequest = { viewModel.hidePeerDropdownMenu() }) {
                            DropdownMenuItem(
                                leadingIcon = {
                                  Icon(
                                      painter = painterResource(R.drawable.clipboard),
                                      contentDescription = null)
                                },
                                text = { Text(text = stringResource(R.string.copy_ip_address)) },
                                onClick = {
                                  viewModel.copyIpAddress(peer, localClipboardManager)
                                  viewModel.hidePeerDropdownMenu()
                                })
                            netmap.value?.let { netMap ->
                              if (!peer.isSelfNode(netMap)) {
                                DropdownMenuItem(
                                    leadingIcon = {
                                      Icon(
                                          painter = painterResource(R.drawable.timer),
                                          contentDescription = null)
                                    },
                                    text = { Text(text = stringResource(R.string.ping)) },
                                    onClick = {
                                      viewModel.hidePeerDropdownMenu()
                                      viewModel.startPing(peer)
                                    })
                              }
                            }
                          }
                    }
                  },
                  supportingContent = {
                    Text(
                        text = peer.Addresses?.first()?.split("/")?.first() ?: "",
                        style =
                            MaterialTheme.typography.bodyMedium.copy(
                                lineHeight = MaterialTheme.typography.titleMedium.lineHeight))
                  })
            }
          }
        }
  }
}

@Composable
fun NodesSectionHeader(peerSet: PeerSet) {
  Spacer(Modifier.height(16.dp).fillMaxSize().background(color = MaterialTheme.colorScheme.surface))

  Lists.LargeTitle(
      peerSet.user?.DisplayName ?: stringResource(id = R.string.unknown_user),
      bottomPadding = 8.dp,
      focusable = isAndroidTV(),
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.SemiBold)
}

@Composable
fun ExpiryNotification(netmap: Netmap.NetworkMap?, action: () -> Unit = {}) {
  if (netmap == null) return

  Box(modifier = Modifier.background(color = MaterialTheme.colorScheme.surfaceContainer)) {
    Box(
        modifier =
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                .clip(shape = RoundedCornerShape(10.dp, 10.dp, 10.dp, 10.dp))
                .fillMaxWidth()) {
          ListItem(
              modifier = Modifier.clickable { action() },
              colors = MaterialTheme.colorScheme.warningListItem,
              headlineContent = {
                Text(
                    netmap.SelfNode.expiryLabel(),
                    style = MaterialTheme.typography.titleMedium,
                )
              },
              supportingContent = {
                Text(
                    stringResource(id = R.string.keyExpiryExplainer),
                    style = MaterialTheme.typography.bodyMedium)
              })
        }
  }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PromptForMissingPermissions(viewModel: MainViewModel) {
  if (viewModel.skipPromptsForAuthKeyLogin()) {
    return
  }

  Permissions.prompt.forEach { (permission, state) ->
    ErrorDialog(
        title = permission.title,
        message = permission.description,
        buttonText = R.string._continue) {
          state.launchPermissionRequest()
        }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Search(
    onSearchBarClick: () -> Unit, // Callback for navigating to SearchView
    backgroundColor: Color = MaterialTheme.colorScheme.background, // Default background color
) {
  // Prevent multiple taps
  var isNavigating by remember { mutableStateOf(false) }

  Box(
      modifier =
          Modifier.fillMaxWidth()
              .background(MaterialTheme.colorScheme.surface)
              .padding(top = 8.dp)) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                    .height(56.dp)
                    .clip(MaterialTheme.shapes.extraLarge) // Rounded corners for search bar
                    .background(backgroundColor) // Search bar background
                    .clickable(enabled = !isNavigating) { // Intercept taps
                      isNavigating = true
                      onSearchBarClick()
                    }
                    .padding(horizontal = 16.dp) // Internal padding
            ) {
              Row(
                  verticalAlignment = Alignment.CenterVertically, // Ensure icon aligns with text
                  modifier = Modifier.fillMaxSize()) {
                    // Leading Icon
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier.padding(start = 0.dp) // Optional start padding for alignment
                        )
                    Spacer(modifier = Modifier.width(4.dp))

                    // Placeholder Text
                    Text(
                        text = stringResource(R.string.search_ellipsis),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f) // Ensure text takes up remaining space
                        )
                  }
            }
      }
}

@Preview
@Composable
fun MainViewPreview() {
  val vpnViewModel = VpnViewModel(App.get())
  val vm = MainViewModel(vpnViewModel)

  MainView(
      {},
      MainViewNavigation(
          onNavigateToSettings = {},
          onNavigateToPeerDetails = {},
          onNavigateToExitNodes = {},
          onNavigateToHealth = {},
          onNavigateToSearch = {}),
      vm)
}
