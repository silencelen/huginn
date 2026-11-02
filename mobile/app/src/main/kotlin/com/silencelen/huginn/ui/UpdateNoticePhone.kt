package com.silencelen.huginn.ui

import androidx.compose.runtime.Composable
import com.silencelen.huginn.update.PhoneUpdateNotice

/**
 * The shared [UpdateNoticeCard] wired to this app, at the top of the chats list.
 *
 * The button is the step the updater is at ([PhoneUpdateNotice.buttonLabel]):
 * Download, named with its size because that tap spends data; inert while it
 * downloads; Install and restart once the APK is verified, which hands it to the
 * system installer and its own question. Later is this run's
 * ([HuginnViewModel.updateLater]); Don't remind me is this version's
 * ([HuginnViewModel.updateSilence]). Nothing here installs anything on its own.
 */
@Composable
fun UpdateNoticePhone(vm: HuginnViewModel, notice: PhoneUpdateNotice) {
    UpdateNoticeCard(
        version = notice.version,
        line = notice.line,
        installable = true,
        hint = "",
        command = null,
        onInstall = { vm.updateNoticeAct(notice) },
        onCopyCommand = {},
        onLater = { vm.updateLater(notice.version) },
        onSilence = { vm.updateSilence(notice.version) },
        installLabel = notice.buttonLabel,
        installEnabled = notice.buttonEnabled,
    )
}
