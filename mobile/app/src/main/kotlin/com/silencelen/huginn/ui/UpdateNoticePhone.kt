package com.silencelen.huginn.ui

import androidx.compose.runtime.Composable
import com.silencelen.huginn.update.PhoneUpdateNotice

/**
 * The shared [UpdateNoticeCard] wired to this app, at the top of the chats list.
 *
 * The button is the hand-off ([PhoneUpdateNotice.buttonLabel]): Open devstore,
 * the store app that installs the release, or Open release page on a phone
 * without it ([HuginnViewModel.openUpdate]). Later is this run's
 * ([HuginnViewModel.updateLater]); Don't remind me is this version's
 * ([HuginnViewModel.updateSilence]). Nothing here installs anything.
 */
@Composable
fun UpdateNoticePhone(vm: HuginnViewModel, notice: PhoneUpdateNotice) {
    UpdateNoticeCard(
        version = notice.version,
        line = notice.line,
        installable = true,
        hint = "",
        command = null,
        onInstall = { vm.openUpdate(notice.handoff) },
        onCopyCommand = {},
        onLater = { vm.updateLater(notice.version) },
        onSilence = { vm.updateSilence(notice.version) },
        installLabel = notice.buttonLabel,
    )
}
