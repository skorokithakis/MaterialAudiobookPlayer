/*
 * This file is part of Material Audiobook Player.
 *
 * Material Audiobook Player is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or any later version.
 *
 * Material Audiobook Player is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Material Audiobook Player. If not, see <http://www.gnu.org/licenses/>.
 * /licenses/>.
 */

package de.ph1b.audiobook.features.book_playing

import android.content.Intent
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.support.v4.view.ViewCompat
import android.support.v7.app.AppCompatActivity
import android.text.format.DateUtils
import android.view.*
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import butterknife.bindView
import com.getbase.floatingactionbutton.FloatingActionButton
import com.jakewharton.rxbinding.view.clicks
import com.jakewharton.rxbinding.widget.RxAdapterView
import com.jakewharton.rxbinding.widget.RxSeekBar
import com.jakewharton.rxbinding.widget.SeekBarProgressChangeEvent
import com.jakewharton.rxbinding.widget.SeekBarStopChangeEvent
import com.squareup.picasso.Picasso
import de.ph1b.audiobook.Book
import de.ph1b.audiobook.R
import de.ph1b.audiobook.features.bookmarks.BookmarkDialogFragment
import de.ph1b.audiobook.features.settings.SettingsActivity
import de.ph1b.audiobook.features.settings.dialogs.PlaybackSpeedDialogFragment
import de.ph1b.audiobook.injection.App
import de.ph1b.audiobook.misc.MultiLineSpinnerAdapter
import de.ph1b.audiobook.misc.actionBar
import de.ph1b.audiobook.persistence.BookChest
import de.ph1b.audiobook.persistence.BookmarkProvider
import de.ph1b.audiobook.persistence.PrefsManager
import de.ph1b.audiobook.playback.PlayStateManager
import de.ph1b.audiobook.playback.PlayerController
import de.ph1b.audiobook.playback.Sandman
import de.ph1b.audiobook.playback.utils.MediaPlayerCapabilities
import de.ph1b.audiobook.uitools.CoverReplacement
import de.ph1b.audiobook.uitools.PlayPauseDrawable
import de.ph1b.audiobook.uitools.ThemeUtil
import i
import rx.Observable
import rx.functions.Action1
import rx.subscriptions.CompositeSubscription
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject


/**
 * Base class for book playing interaction.

 * @author Paul Woitaschek
 */
class BookPlayFragment : Fragment() {

    init {
        App.component().inject(this)
    }

    @Inject internal lateinit var mediaPlayer: PlayerController
    @Inject internal lateinit var sandMan: Sandman
    @Inject internal lateinit var prefs: PrefsManager
    @Inject internal lateinit var bookChest: BookChest
    @Inject internal lateinit var bookmarkProvider: BookmarkProvider
    @Inject internal lateinit var playStateManager: PlayStateManager
    @Inject internal lateinit var playerCapabilities: MediaPlayerCapabilities

    private val playPauseDrawable = PlayPauseDrawable()
    private var subscriptions: CompositeSubscription? = null
    private var book: Book? = null

    private val hostingActivity: AppCompatActivity by lazy { context as AppCompatActivity }

    private val timerCountdownView: TextView by bindView(R.id.timerView)
    private val playedTimeView: TextView by bindView(R.id.played)
    private val seekBar: SeekBar by bindView(R.id.seekBar)
    private val bookSpinner: Spinner by bindView(R.id.book_spinner)
    private val maxTimeView: TextView by bindView(R.id.maxTime)
    private val coverFrame: View by bindView(R.id.cover_frame)
    private val coverView: ImageView by bindView(R.id.book_cover)
    private val nextButton: View  by bindView(R.id.next)
    private val fastForwardButton: View by bindView(R.id.fastForward)
    private val playButton: FloatingActionButton by bindView(R.id.play)
    private val rewindButton: View by bindView(R.id.rewind)
    private val previousButton: View by bindView(R.id.previous)

    /**
     * @return the book id this fragment was instantiated with.
     */
    val bookId: Long
        get() = arguments.getLong(NI_BOOK_ID)

    override fun onViewCreated(view: View?, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        playButton.setOnClickListener { mediaPlayer.playPause() }
        rewindButton.setOnClickListener { mediaPlayer.rewind() }
        fastForwardButton.setOnClickListener { mediaPlayer.fastForward() }
        nextButton.setOnClickListener { mediaPlayer.next() }
        previousButton.setOnClickListener { mediaPlayer.previous() }
        playedTimeView.setOnClickListener { launchJumpToPositionDialog() }

        var lastClick = 0L
        val doubleClickTime = ViewConfiguration.getDoubleTapTimeout()
        coverFrame.clicks()
                .filter {
                    val currentTime = System.currentTimeMillis()
                    val doubleClick = currentTime - lastClick < doubleClickTime
                    lastClick = currentTime
                    doubleClick
                }
                .doOnNext { lastClick = 0 } // resets so triple clicks won't cause another invoke
                .onBackpressureLatest()
                .subscribe { mediaPlayer.playPause() }

        book = bookChest.bookById(bookId)

        //init views
        val actionBar = actionBar().apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_arrow_back)
        }

        //setup buttons
        playButton.setIconDrawable(playPauseDrawable)
        RxSeekBar.changeEvents(seekBar)
                .subscribe {
                    when (it ) {
                        is  SeekBarProgressChangeEvent -> {
                            //sets text to adjust while using seekBar
                            playedTimeView.text = formatTime(it.progress().toLong(), seekBar.max.toLong())
                        }
                        is SeekBarStopChangeEvent -> {
                            val progress = seekBar.progress
                            mediaPlayer.changePosition(progress, book!!.currentChapter().file)
                            playedTimeView.text = formatTime(progress.toLong(), seekBar.max.toLong())
                        }
                    }
                }

        if (book != null) {
            actionBar.title = book!!.name

            // adapter
            val chapters = book!!.chapters
            val chapterNames = ArrayList<MultiLineSpinnerAdapter.Data<String>>(chapters.size)
            for (i in chapters.indices) {
                var chapterName = chapters[i].name

                // cutting leading zeros
                chapterName = chapterName.replaceFirst("^0".toRegex(), "")
                val number = (i + 1).toString()

                // desired format is "1 - Title"
                if (!chapterName.startsWith(number + " - ")) {
                    // if getTitle does not match desired format
                    if (chapterName.startsWith(number)) {
                        // if it starts with a number, a " - " should follow
                        chapterName = number + " - " + chapterName.substring(chapterName.indexOf(number) + number.length)
                    } else {
                        // if the name does not match at all, set the correct format
                        chapterName = number + " - " + chapterName
                    }
                }

                chapterNames.add(MultiLineSpinnerAdapter.Data(chapterName, chapterName))
            }

            val adapter = MultiLineSpinnerAdapter<String>(bookSpinner, context, ContextCompat.getColor(context, ThemeUtil.getResourceId(context, android.R.attr.textColorPrimary)))
            adapter.setData(chapterNames)
            bookSpinner.adapter = adapter
            //bookSpinner.adapter = spinnerAdapter
            RxAdapterView.itemSelections(bookSpinner).subscribe {
                // fire event only when that tag has been set (= this is not the first event) and
                // this is a new value
                val realInput = bookSpinner.tag != null && bookSpinner.tag != it
                if (realInput) {
                    i { "spinner: onItemSelected. firing: $it" }
                    mediaPlayer.changePosition(0, book!!.chapters[it].file)
                    bookSpinner.tag = it
                }
            }

            // Next/Prev/spinner/book progress views hiding
            if (book!!.chapters.size == 1) {
                nextButton.visibility = View.GONE
                previousButton.visibility = View.GONE
                bookSpinner.visibility = View.GONE
            } else {
                nextButton.visibility = View.VISIBLE
                previousButton.visibility = View.VISIBLE
                bookSpinner.visibility = View.VISIBLE
            }

            ViewCompat.setTransitionName(coverView, book!!.coverTransitionName)
        }

        // (Cover)
        val coverReplacement = CoverReplacement(if (book == null) "M" else book!!.name, context)
        if (book != null && !book!!.useCoverReplacement && book!!.coverFile().canRead()) {
            Picasso.with(context).load(book!!.coverFile()).placeholder(coverReplacement).into(coverView)
        } else {
            // we have to set the cover in onPreDraw. Else the transition will fail.
            coverView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    coverView.viewTreeObserver.removeOnPreDrawListener(this)
                    coverView.setImageDrawable(coverReplacement)
                    return true
                }
            })
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
            inflater.inflate(R.layout.fragment_book_play, container, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)
    }


    private fun launchJumpToPositionDialog() {
        JumpToPositionDialogFragment().show(fragmentManager, JumpToPositionDialogFragment.TAG)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.book_play, menu)

        val speedItem = menu.findItem(R.id.action_time_lapse)
        speedItem.isEnabled = playerCapabilities.useCustomMediaPlayer

        // sets the correct sleep timer icon
        val sleepTimerItem = menu.findItem(R.id.action_sleep)
        if (sandMan.sleepTimerActive()) {
            sleepTimerItem.setIcon(R.drawable.alarm_off)
        } else {
            sleepTimerItem.setIcon(R.drawable.alarm)
        }

        // hide bookmark and getTime change item if there is no valid book
        val currentBookExists = book != null
        val bookmarkItem = menu.findItem(R.id.action_bookmark)
        val timeChangeItem = menu.findItem(R.id.action_time_change)
        bookmarkItem.isVisible = currentBookExists
        timeChangeItem.isVisible = currentBookExists
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(context, SettingsActivity::class.java))
                return true
            }
            R.id.action_time_change -> {
                launchJumpToPositionDialog()
                return true
            }
            R.id.action_sleep -> {
                sandMan.toggleSleepSand()
                if (prefs.setBookmarkOnSleepTimer() && sandMan.sleepTimerActive()) {
                    val date = DateUtils.formatDateTime(context, System.currentTimeMillis(), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_NUMERIC_DATE)
                    bookmarkProvider.addBookmarkAtBookPosition(book!!, date + ": " + getString(R.string.action_sleep))
                }
                return true
            }
            R.id.action_time_lapse -> {
                PlaybackSpeedDialogFragment().show(fragmentManager,
                        PlaybackSpeedDialogFragment.TAG)
                return true
            }
            R.id.action_bookmark -> {
                BookmarkDialogFragment.newInstance(bookId).show(fragmentManager,
                        BookmarkDialogFragment.TAG)
                return true
            }
            android.R.id.home -> {
                hostingActivity.onBackPressed()
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    override fun onStart() {
        super.onStart()

        subscriptions = CompositeSubscription().apply {
            add(playStateManager.playState
                    .subscribe(object : Action1<PlayStateManager.PlayState> {
                        private var firstRun = true

                        override fun call(playState: PlayStateManager.PlayState) {
                            // animate only if this is not the first run
                            i { "onNext with playState $playState" }
                            if (playState === PlayStateManager.PlayState.PLAYING) {
                                playPauseDrawable.transformToPause(!firstRun)
                            } else {
                                playPauseDrawable.transformToPlay(!firstRun)
                            }

                            firstRun = false
                        }
                    }))

            add(Observable.merge(Observable.from(bookChest.activeBooks), bookChest.updateObservable())
                    .filter { it.id == bookId }
                    .subscribe { book: Book ->
                        this@BookPlayFragment.book = book

                        val chapters = book.chapters
                        val chapter = book.currentChapter()

                        val position = chapters.indexOf(chapter)
                        /* Setting position as a tag, so we can make sure onItemSelected is only fired when
                         the user changes the position himself.  */
                        bookSpinner.tag = position
                        bookSpinner.setSelection(position, true)
                        val duration = chapter.duration
                        seekBar.max = duration
                        maxTimeView.text = formatTime(duration.toLong(), duration.toLong())

                        // Setting seekBar and played getTime view
                        val progress = book.time
                        if (!seekBar.isPressed) {
                            seekBar.progress = progress
                            playedTimeView.text = formatTime(progress.toLong(), duration.toLong())
                        }
                    })

            // hide / show left time view
            add(sandMan.sleepSand
                    .map { if (it > 0) View.VISIBLE else View.GONE }
                    .distinctUntilChanged() // only set when visibility has changed
                    .subscribe { visibility ->
                        timerCountdownView.visibility = visibility
                    })

            // invalidates the actionbar items
            add(sandMan.sleepSand
                    .map { it > 0 } // sleep timer is active
                    .distinctUntilChanged() // only notify when event has changed
                    .subscribe { hostingActivity.invalidateOptionsMenu() }
            )

            // set the correct time to the sleep time view
            add(sandMan.sleepSand
                    .distinctUntilChanged()
                    .filter { it > 0 }
                    .map { formatTime(it, it) }
                    .subscribe { timerCountdownView.text = it })
        }

    }

    override fun onStop() {
        super.onStop()

        subscriptions!!.unsubscribe()
    }

    private fun formatTime(ms: Long, duration: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms.toLong()).toString()
        val m = "%02d".format((TimeUnit.MILLISECONDS.toMinutes(ms.toLong()) % 60))
        val s = "%02d".format((TimeUnit.MILLISECONDS.toSeconds(ms.toLong()) % 60))

        if (TimeUnit.MILLISECONDS.toHours(duration.toLong()) == 0L) {
            return m + ":" + s
        } else {
            return "$h:$m:$s"
        }
    }

    companion object {

        val TAG = BookPlayFragment::class.java.simpleName
        private val NI_BOOK_ID = "niBookId"


        /**
         * Method to create a new instance of this fragment. Do not create a new instance yourself.

         * @param bookId the id to use
         * *
         * @return The new instance
         */
        fun newInstance(bookId: Long) = BookPlayFragment().apply {
            arguments = Bundle().apply {
                putLong(NI_BOOK_ID, bookId)
            }
        }
    }
}
