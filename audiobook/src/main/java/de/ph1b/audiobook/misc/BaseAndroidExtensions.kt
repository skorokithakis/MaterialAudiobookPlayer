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

package de.ph1b.audiobook.misc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.support.v4.app.Fragment
import android.support.v7.app.AppCompatActivity
import android.view.LayoutInflater
import de.ph1b.audiobook.BuildConfig


fun Fragment.actionBar() = (activity as AppCompatActivity).supportActionBar!!

inline fun<reified T : Activity> Activity.startActivity(args: Bundle? = null, flags: Int? = null) {
    val intent = Intent(this, T::class.java)
    args?.let { intent.putExtras(args) }
    flags?.let { intent.flags = flags }
    startActivity(intent)
}

fun Context.layoutInflater() = LayoutInflater.from(this)

fun assertMain() {
    if (BuildConfig.DEBUG) {
        if (Looper.getMainLooper().thread != Thread.currentThread()) {
            throw IllegalArgumentException("Must call on main thread")
        }
    }
}