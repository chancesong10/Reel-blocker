package com.snoozy.app

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

// Which DM chats' Reels are allowed to play.
class FriendsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_friends)
        findViewById<TextView>(R.id.pageTitle).text = "💌  Friends' Reels"
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        findViewById<View>(R.id.allowAll).setOnClickListener {
            FriendsStore.setAll(this, true)
            renderFriends()
        }
        findViewById<View>(R.id.blockAll).setOnClickListener {
            FriendsStore.setAll(this, false)
            renderFriends()
        }
    }

    override fun onResume() {
        super.onResume()
        renderFriends()
    }

    private fun renderFriends() {
        val list = findViewById<LinearLayout>(R.id.friendsList)
        list.removeAllViews()
        val friends = FriendsStore.all(this)
        val inflater = LayoutInflater.from(this)
        for ((name, allowed) in friends) {
            val row = inflater.inflate(R.layout.item_friend, list, false)
            row.findViewById<TextView>(R.id.friendName).text = name
            val switch = row.findViewById<Switch>(R.id.friendSwitch)
            switch.isChecked = allowed
            switch.setOnCheckedChangeListener { _, checked -> FriendsStore.set(this, name, checked) }
            list.addView(row)
        }
        findViewById<View>(R.id.friendsEmpty).visibility = if (friends.isEmpty()) View.VISIBLE else View.GONE
    }
}
