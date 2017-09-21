package me.shoutto.sdk.internal.http;

import org.json.JSONObject;

import java.lang.reflect.Type;

import me.shoutto.sdk.StmBaseEntity;

/**
 * Interface for adapting a JSON response from the Shout to Me service to a Shout to Me entity
 */

public interface StmHttpResponseAdapter<T extends StmBaseEntity> {

    T adapt(JSONObject jsonObject, String serializationKey, Type typeOfT);
}