package com.sidharth.reco.chat;

import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.sidharth.reco.MainActivity;
import com.sidharth.reco.R;
import com.sidharth.reco.chat.callback.OnChatOptionClickListener;
import com.sidharth.reco.chat.callback.OnSongLongClickedListener;
import com.sidharth.reco.chat.controller.ChatAdapter;
import com.sidharth.reco.chat.model.ChatModel;
import com.sidharth.reco.chat.model.ChatOptionModel;
import com.sidharth.reco.chat.model.SongModel;
import com.sidharth.reco.recommender.RecoBrain;
import com.sidharth.reco.recommender.SongFeatureModel;
import com.sidharth.reco.recommender.SongRecommender;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;


public class ChatActivity extends AppCompatActivity implements OnChatOptionClickListener, OnSongLongClickedListener {

    private Handler handler;
    private Runnable runnable;

    public static final int SENDER_BOT = 1;
    public static final int SENDER_USER = 2;
    public static final int SONG_VIEW = 3;

    public static final int TYPE_MOOD = 101;
    public static final int TYPE_TRY_NEW = 102;
    public static final int TYPE_FEEDBACK = 103;
    public static final int TYPE_SHOW_SIMILAR = 104;
    public static final int TYPE_OPTION_MENU = 105;

    private static final ArrayList<String> MOODS = new ArrayList<>(Arrays.asList("happy", "calm", "anxious", "energetic"));
    private static final ArrayList<String> FEEDBACK = new ArrayList<>(Arrays.asList("Yes", "No"));

    private ArrayList<ChatModel> chats;
    private ChatAdapter chatAdapter;
    private RecyclerView recyclerView;

    private static final String BASE_URL = "https://spotify23.p.rapidapi.com/tracks/?ids=";
    private boolean songClicked = false;
    private SongModel songModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        SongRecommender.initializeSongData(this);

        // intro chats
        String recoIntro = "Hi,\nI'm Reco, your personal bot\nLet's listen to some songs";
        String moodMessage = "How are your feeling today?";
        ChatOptionModel moodOptionModel = new ChatOptionModel(TYPE_MOOD, MOODS);
        chats = new ArrayList<>(
                Arrays.asList(
                        new ChatModel(SENDER_BOT, recoIntro),
                        new ChatModel(SENDER_BOT, moodMessage, moodOptionModel)
                ));

        // chat adapter
        chatAdapter = new ChatAdapter(this, chats, this);
        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setAdapter(chatAdapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // send button
        FloatingActionButton sendBtn = findViewById(R.id.sendButton);
        sendBtn.setOnClickListener(view -> {
            EditText inET = findViewById(R.id.in_message);
            String message = String.valueOf(inET.getText());
            inET.setText("");
            if (!TextUtils.isEmpty(message)) {
                stopHandler();
                removeOptions();
                ChatModel chatModel = new ChatModel(SENDER_USER, message);
                addConversationToChats(chatModel);
                ChatModel answer = RecoBrain.analyzeChat(message);
                handler.postDelayed(() -> replyToUser(answer), 1000);
            }
            closeKeyboard();
        });

        handler = new Handler();
        runnable = () -> {
            removeOptions();
            ChatModel chatModel = new ChatModel(SENDER_BOT, getString(R.string.msg_no_response));
            addConversationToChats(chatModel);
            stopHandler();
        };
        startHandler();
    }

    private void removeOptions() {
        if (chats.get(chats.size() - 1).getSender() == SENDER_BOT) {
            ChatModel chatModel = chats.get(chats.size() - 1);
            ChatModel newChatModel = new ChatModel(chatModel.getSender(), chatModel.getMessage());
            chats.remove(chats.size() - 1);
            chats.add(newChatModel);
            chatAdapter.notifyItemChanged(chats.size() - 1);
        }
    }

    private void replyToUser(ChatModel chatModel) {
        addConversationToChats(chatModel);
    }

    private void addConversationToChats(ChatModel chatModel) {
        if (chatModel != null) {
            chats.add(chatModel);
            playMessagingSound(chatModel.getSender());
        } else {
            ChatModel chat = new ChatModel(SENDER_BOT, getString(R.string.msg_try_new));
            addConversationToChats(chat);
            playMessagingSound(SENDER_BOT);
        }
        recyclerView.smoothScrollToPosition(chats.size() - 1);
        chatAdapter.notifyItemInserted(chats.size());
    }

    private void playMessagingSound(int sender) {
        MediaPlayer player;
        if (sender == SENDER_USER) {
            player = MediaPlayer.create(this, R.raw.sent);
        } else {
            player = MediaPlayer.create(this, R.raw.recieved);
        }
        if (player.isPlaying()) {
            player.stop();
        }
        player.start();
        player.setOnCompletionListener(mediaPlayer -> player.release());
    }

    private void recommendSong(int type, int position) {
        switch (type) {
            case TYPE_MOOD: {
                SongFeatureModel song = SongRecommender.getMoodSong(position);
                parseJSON(song);
                break;
            }
            case TYPE_SHOW_SIMILAR: {
                if (position == 0) {
                    ArrayList<SongFeatureModel> songs = SongRecommender.getSimilarSongs(songModel);
                    for (SongFeatureModel song : songs) {
                        handler.postDelayed((Runnable) () -> parseJSON(song), 500);
                    }
                } else {
                    tryNewSong();
                }
                break;
            }
            case TYPE_FEEDBACK: {
                if (position == 0) {
                    wantSimilarSong();
                } else {
                    tryNewSong();
                }
                break;
            }
            case TYPE_TRY_NEW: {
                if (position == 0) {
                    SongFeatureModel song = SongRecommender.getNewSong();
                    assert song != null;
                    parseJSON(song);
                } else {
                    ChatModel chatModel = new ChatModel(SENDER_BOT, getString(R.string.msg_thanks));
                    addConversationToChats(chatModel);
                }
                break;
            }
            case TYPE_OPTION_MENU: {
                switch (position) {
                    case 0: {
                        String message = "Hi I am Reco\n" +
                                "My father name is Mr. Sidharth Mudgil\n" +
                                "He is my inspiration\n" +
                                "He is human and I am bot\n" +
                                "But he loves me so much";
                        ChatModel chatModel = new ChatModel(SENDER_BOT, message);
                        addConversationToChats(chatModel);
                        break;
                    }
                    case 1: {
                        String day = null;
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            day = LocalDate.now().getDayOfWeek().name();
                        }
                        ChatModel chatModel = new ChatModel(SENDER_BOT, day);
                        addConversationToChats(chatModel);
                        break;
                    }
                    case 2: {
                        final String[] jokes = {
                                "Two bytes meet.  The first byte asks, “Are you ill?”\n" +
                                        "The second byte replies, “No, just feeling a bit off.”",
                                "Eight bytes walk into a bar.  The bartender asks, “Can I get you anything?”\n" +
                                        "“Yeah,” reply the bytes.  “Make us a double.”",
                                "Q. How did the programmer die in the shower?\n" +
                                        "A. He read the shampoo bottle instructions: Lather. Rinse. Repeat."
                        };
                        ChatModel chatModel = new ChatModel(SENDER_BOT, jokes[new Random().nextInt(jokes.length)]);
                        addConversationToChats(chatModel);
                        break;
                    }
                    case 3: {
                        ChatOptionModel optionModel = new ChatOptionModel(TYPE_MOOD, MOODS);
                        ChatModel chatModel = new ChatModel(SENDER_BOT, getString(R.string.msg_thanks), optionModel);
                        addConversationToChats(chatModel);
                        break;
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    private void likedTheSong() {
        ChatOptionModel optionModel = new ChatOptionModel(TYPE_FEEDBACK, FEEDBACK);
        ChatModel chatModel = new ChatModel(SENDER_BOT, getString(R.string.msg_liked_the_song), optionModel);
        addConversationToChats(chatModel);
        startHandler();
    }

    private void wantSimilarSong() {
        ChatOptionModel optionModel = new ChatOptionModel(TYPE_SHOW_SIMILAR, FEEDBACK);
        ChatModel chatModel = new ChatModel(SENDER_BOT, getString(R.string.msg_want_similar), optionModel);
        addConversationToChats(chatModel);
        startHandler();
    }

    private void tryNewSong() {
        ChatOptionModel optionModel = new ChatOptionModel(TYPE_TRY_NEW, FEEDBACK);
        ChatModel chatModel = new ChatModel(SENDER_BOT, getString(R.string.msg_try_new1), optionModel);
        addConversationToChats(chatModel);
        startHandler();
    }

    private void parseJSON(SongFeatureModel featureModel) {
        final String URL = BASE_URL + featureModel.getId();
        RequestQueue requestQueue = Volley.newRequestQueue(this);

        JsonObjectRequest objectRequest = new JsonObjectRequest(Request.Method.GET, URL, null,
                response -> {
                    try {
                        JSONArray jsonArray = response.getJSONArray("tracks");
                        JSONObject jsonObject = jsonArray.getJSONObject(0);

                        // for spotify url
                        JSONObject urls = jsonObject.getJSONObject("external_urls");
                        String spotify_url = urls.getString("spotify");

                        // for artists
                        JSONArray artists = jsonObject.getJSONArray("artists");
                        StringBuilder artist = new StringBuilder();
                        for (int i = 0; i < artists.length(); i++) {
                            JSONObject object = artists.getJSONObject(i);
                            String name = object.getString("name");
                            artist.append(name);
                            if (i != artists.length() - 1) {
                                artist.append(", ");
                            }
                        }

                        // for image url
                        JSONObject album = jsonObject.getJSONObject("album");
                        JSONArray images = album.getJSONArray("images");
                        JSONObject image = images.getJSONObject(2);
                        String imgURL = image.getString("url");

                        String songName = jsonObject.getString("name");

                        SongModel songModel = new SongModel(imgURL, songName, String.valueOf(artist), spotify_url, featureModel);
                        ChatModel chatModel = new ChatModel(SONG_VIEW, songModel);
                        addConversationToChats(chatModel);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                },
                error -> Log.d(MainActivity.TAG, error.getMessage())) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> params = new HashMap<>();
                params.put("X-RapidAPI-Host", getString(R.string.api_host));
                params.put("X-RapidAPI-Key", getString(R.string.api_key));
                return params;
            }
        };
        requestQueue.add(objectRequest);
    }

    public void stopHandler() {
        handler.removeCallbacksAndMessages(null);
    }

    public void startHandler() {
        handler.postDelayed(runnable, 15 * 1000);
    }

    private void closeKeyboard() {
        InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        inputMethodManager.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0);
    }

    @Override
    public void onOptionClicked(ChatOptionModel optionModel, int position) {
        removeOptions();
        String message = optionModel.getOptions().get(position);
        ChatModel chatModel = new ChatModel(SENDER_USER, message);
        addConversationToChats(chatModel);
        handler.postDelayed(() -> recommendSong(optionModel.getType(), position), 1000);
    }

    @Override
    public void askUserFeedback(SongModel songModel) {
        songClicked = true;
        this.songModel = songModel;
    }

    @Override
    protected void onStop() {
        super.onStop();
        removeOptions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (songClicked) {
            handler.postDelayed(this::likedTheSong, 1000);
            songClicked = false;
        }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        stopHandler();
    }
}
