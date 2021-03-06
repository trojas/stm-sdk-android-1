package me.shoutto.sdk.internal.http;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.Date;

import me.shoutto.sdk.StmBaseEntity;

/**
 * Gson adapter for converting Shout to Me entities to JSON objects
 */

public class GsonRequestAdapter<T> implements StmJsonRequestAdapter<T> {
    @Override
    public String adapt(T objectToAdapt) {
        Gson gson = new GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .registerTypeAdapter(Date.class, new GsonDateAdapter())
                .create();
        return gson.toJson(objectToAdapt, objectToAdapt.getClass());
    }
}
