package de.westnordost.streetcomplete.screens.main.messages

import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import de.westnordost.streetcomplete.R
import de.westnordost.streetcomplete.data.messages.Message
import de.westnordost.streetcomplete.data.messages.NewAchievementMessage
import de.westnordost.streetcomplete.data.messages.NewVersionMessage
import de.westnordost.streetcomplete.data.messages.OsmUnreadMessagesMessage
import de.westnordost.streetcomplete.data.messages.QuestSelectionHintMessage
import de.westnordost.streetcomplete.screens.about.WhatsNewDialog
import de.westnordost.streetcomplete.screens.settings.SettingsActivity

/** A fragment that contains any fragments that would show messages.
 *  Usually, messages are shown as dialogs, however there is currently one exception which
 *  makes this necessary as a fragment */
class MessagesContainerFragment : Fragment() {

    fun showMessage(message: Message) {
        val ctx = context ?: return
        when (message) {
            is OsmUnreadMessagesMessage -> {
                OsmUnreadMessagesFragment
                    .create(message.unreadMessages)
                    .show(childFragmentManager, null)
            }
            is NewVersionMessage -> {
                WhatsNewDialog(ctx, message.sinceVersion)
                    .show()
            }
            is NewAchievementMessage -> {
                // TODO
            }
            is QuestSelectionHintMessage -> {
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.quest_selection_hint_title)
                    .setMessage(R.string.quest_selection_hint_message)
                    .setPositiveButton(R.string.quest_streetName_cantType_open_settings) { _, _ ->
                        startActivity(SettingsActivity.createLaunchQuestSettingsIntent(ctx))
                    }
                    .setNegativeButton(android.R.string.ok, null)
                    .show()
            }
        }
    }
}
