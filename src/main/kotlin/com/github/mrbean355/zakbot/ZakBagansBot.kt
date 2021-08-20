package com.github.mrbean355.zakbot

import com.github.mrbean355.zakbot.phrases.Phrase
import com.github.mrbean355.zakbot.substitutions.substitute
import com.github.mrbean355.zakbot.util.getString
import net.dean.jraw.models.Comment
import net.dean.jraw.models.PublicContribution
import net.dean.jraw.models.Submission
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.Date
import javax.annotation.PostConstruct
import kotlin.random.Random

@Component
class ZakBagansBot(
    private val redditService: RedditService,
    private val telegramNotifier: TelegramNotifier,
    private val cache: Cache,
    phrases: List<Phrase>
) {

    private val phrases = phrases.sortedByDescending { it.priority }

    @Value("\${zakbot.replies.enabled:false}")
    private var sendReplies = false

    @PostConstruct
    fun onPostConstruct() {
        telegramNotifier.sendMessage(getString("telegram.bot_start_up", AppVersion))
    }

    @Scheduled(fixedRate = 5 * 60 * 1000L)
    fun checkComments() {
        redditService.getSubmissionsSince(Date(cache.getLastPost())).apply {
            firstOrNull()?.created?.time?.let(cache::setLastPost)
            filterNot { it.isAuthorIgnored() }
                .forEach(::processSubmission)
        }

        redditService.getCommentsSince(Date(cache.getLastComment())).apply {
            firstOrNull()?.created?.time?.let(cache::setLastComment)
            filterNot { it.isAuthorIgnored() }
                .forEach(::processComment)
        }
    }

    private fun processSubmission(submission: Submission) {
        val response = findPhrase(submission.title, submission.body)
            ?.substitute(submission)
            ?: return

        telegramNotifier.sendMessage(getString("telegram.new_submission", submission.author, submission.title, response))
        if (sendReplies) {
            redditService.replyToSubmission(submission, response)
        }
    }

    private fun processComment(comment: Comment) {
        if (comment.author == BotUsername) {
            return
        }
        if (comment.shouldIgnoreAuthor()) {
            cache.ignoreUser(comment.author)
            telegramNotifier.sendMessage(getString("telegram.new_ignored_user", comment.author))
            redditService.replyToComment(comment, getString("reddit.new_user_ignored"))
            return
        }
        val response = findPhrase(comment.body)
            ?.substitute(comment)
            ?: return

        telegramNotifier.sendMessage(getString("telegram.new_comment", comment.author, comment.body, response))

        if (sendReplies) {
            redditService.replyToComment(comment, response)
        }
    }

    private fun findPhrase(vararg inputs: String?): String? {
        inputs.filterNotNull().forEach { input ->
            val phrase = phrases
                .find { Random.nextFloat() <= it.getReplyChance(input.lowercase()) }
                ?.responses?.take()

            if (phrase != null) {
                return phrase
            }
        }
        return null
    }

    private fun PublicContribution<*>.isAuthorIgnored(): Boolean =
        cache.isUserIgnored(author)

    private fun Comment.shouldIgnoreAuthor(): Boolean {
        val isBadBot = body.filter { it.isLetter() }.equals("badbot", ignoreCase = true)
        return if (isBadBot) {
            redditService.findParentComment(this)
                ?.author == BotUsername
        } else false
    }
}