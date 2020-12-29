package io.getstream.chat.android.ui.messages.adapter.view

import android.content.Context
import android.graphics.Paint
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.getstream.sdk.chat.ImageLoader
import com.getstream.sdk.chat.ImageLoader.load
import com.getstream.sdk.chat.model.ModelType
import com.getstream.sdk.chat.utils.extensions.updateConstraints
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import io.getstream.chat.android.client.models.Message
import io.getstream.chat.android.ui.R
import io.getstream.chat.android.ui.databinding.StreamUiMessageReplyViewBinding
import io.getstream.chat.android.ui.utils.UiUtils
import io.getstream.chat.android.ui.utils.extensions.dpToPx
import io.getstream.chat.android.ui.utils.extensions.dpToPxPrecise
import io.getstream.chat.android.ui.utils.extensions.getColorCompat
import io.getstream.chat.android.ui.utils.extensions.use

public class MessageReplyView : FrameLayout {
    private val binding: StreamUiMessageReplyViewBinding =
        StreamUiMessageReplyViewBinding.inflate(LayoutInflater.from(context), this, true)
    private var ellipsize = false

    public constructor(context: Context) : super(context) {
        init(context, null)
    }

    public constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context, attrs)
    }

    public constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        init(context, attrs)
    }

    private fun init(context: Context, attrs: AttributeSet?) {
        context.obtainStyledAttributes(attrs, R.styleable.MessageReplyView).use {
            ellipsize = it.getBoolean(R.styleable.MessageReplyView_streamUiEllipsize, false)
        }
    }

    public fun setMessage(message: Message, isMine: Boolean) {
        setUserAvatar(message)
        setAvatarPosition(isMine)
        setReplyBackground(message, isMine)
        setAttachmentImage(message)
        setReplyText(message)
    }

    private fun setUserAvatar(message: Message) {
        binding.avatarView.setUserData(message.user)
        binding.avatarView.isVisible = true
    }

    private fun setAvatarPosition(isMine: Boolean) {
        binding.root.updateConstraints {
            clear(R.id.avatarView, ConstraintSet.START)
            clear(R.id.avatarView, ConstraintSet.END)
            clear(R.id.replyContainer, ConstraintSet.START)
            clear(R.id.replyContainer, ConstraintSet.END)
        }
        binding.avatarView.updateLayoutParams<ConstraintLayout.LayoutParams> {
            if (isMine) {
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                startToEnd = R.id.replyContainer
            } else {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToStart = R.id.replyContainer
            }
            marginStart = CONTENT_MARGIN
            marginEnd = CONTENT_MARGIN
        }
        binding.replyContainer.updateLayoutParams<ConstraintLayout.LayoutParams> {
            if (isMine) {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToStart = R.id.avatarView
            } else {
                startToEnd = R.id.avatarView
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            }
            marginStart = CONTENT_MARGIN
            marginEnd = CONTENT_MARGIN
        }
    }

    private fun setReplyBackground(message: Message, isMine: Boolean) {
        val shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setAllCornerSizes(REPLY_CORNER_RADIUS)
            .setBottomLeftCornerSize(if (isMine.not()) 0f else REPLY_CORNER_RADIUS)
            .setBottomRightCornerSize(if (isMine) 0f else REPLY_CORNER_RADIUS)
            .build()
        val isLink = message.attachments
            .lastOrNull()
            ?.type == ModelType.attach_link
        binding.replyContainer.background = MaterialShapeDrawable(shapeAppearanceModel).apply {
            when {
                isLink -> {
                    paintStyle = Paint.Style.FILL
                    setTint(context.getColorCompat(R.color.stream_ui_blue_alice))
                }
                isMine -> {
                    paintStyle = Paint.Style.FILL
                    setTint(context.getColorCompat(R.color.stream_ui_grey_90))
                }
                else -> {
                    paintStyle = Paint.Style.FILL_AND_STROKE
                    setStrokeTint(context.getColorCompat(R.color.stream_ui_border_stroke))
                    strokeWidth = DEFAULT_STROKE_WIDTH
                    setTint(context.getColorCompat(R.color.stream_ui_white))
                }
            }
        }
    }

    private fun setAttachmentImage(message: Message) {
        val attachment = message.attachments.lastOrNull()
        if (attachment == null) {
            binding.logoContainer.isVisible = false
        } else {
            when (attachment.type) {
                ModelType.attach_file -> showFileTypeLogo(attachment.mimeType)
                ModelType.attach_image -> showAttachmentThumb(attachment.imageUrl)
                ModelType.attach_giphy,
                ModelType.attach_video -> showAttachmentThumb(attachment.thumbUrl)
                else -> showAttachmentThumb(attachment.image)
            }
        }
    }

    private fun setReplyText(message: Message) {
        val attachment = message.attachments.lastOrNull()
        binding.replyText.text = if (attachment == null) {
            if (ellipsize) {
                ellipsize(message.text)
            } else {
                message.text
            }
        } else {
            val type = attachment.type
            if (type == ModelType.attach_link) {
                attachment.ogUrl
            } else {
                attachment.title ?: attachment.name
            }
        }
    }

    private fun ellipsize(text: String): String {
        if (text.length <= MAX_ELLIPSIZE_CHAR_COUNT) return text

        return text.substring(0, MAX_ELLIPSIZE_CHAR_COUNT) + "..."
    }

    private fun showAttachmentThumb(url: String?) {
        with(binding) {
            if (url != null) {
                logoContainer.isVisible = true
                thumbImageView.isVisible = true
                fileTypeImageView.isVisible = false
                thumbImageView.load(url, ImageLoader.ImageTransformation.RoundedCorners(REPLY_IMAGE_CORNER_RADIUS))
            } else {
                logoContainer.isVisible = false
            }
        }
    }

    private fun showFileTypeLogo(mimeType: String?) {
        with(binding) {
            logoContainer.isVisible = true
            fileTypeImageView.isVisible = true
            thumbImageView.isVisible = false
            fileTypeImageView.setImageResource(UiUtils.getIcon(mimeType))
        }
    }

    private companion object {
        private val DEFAULT_STROKE_WIDTH = 1.dpToPxPrecise()
        private val REPLY_CORNER_RADIUS = 12.dpToPxPrecise()
        private val REPLY_IMAGE_CORNER_RADIUS = 7.dpToPxPrecise()
        private val CONTENT_MARGIN = 4.dpToPx()
        private const val MAX_ELLIPSIZE_CHAR_COUNT = 170
    }
}