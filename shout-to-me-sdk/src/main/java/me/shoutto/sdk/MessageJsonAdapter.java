package me.shoutto.sdk;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/**
 * Created by tracyrojas on 6/3/16.
 */
public class MessageJsonAdapter implements JsonAdapter<Message> {

    private static final String TAG = "MessageJsonAdapter";
    private StmService stmService;

    public MessageJsonAdapter(StmService stmService) {
        this.stmService = stmService;
    }

    @Override
    public Message adapt(JSONObject jsonObject) throws JSONException {
        Message message = new Message(stmService);

        message.setId(jsonObject.getString("id"));
        message.setMessage(jsonObject.getString("message"));

        String createdDateString = jsonObject.getString("created_date");
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date createdDate = sdf.parse(createdDateString);
            message.setSentDate(createdDate);
        } catch (ParseException ex) {
            Log.w(TAG, "Could not parse created date: " + createdDateString);
        }

        JSONObject channel = jsonObject.getJSONObject("channel");
        message.setChannelName(channel.getString("name"));

        JSONObject sender = jsonObject.getJSONObject("sender");
        String senderName = "";
        try {
            senderName = sender.getString("handle");
        } catch (JSONException ex) {
            senderName = "Anonymous";
        }
        message.setSenderName(senderName);

        return message;
    }
}
