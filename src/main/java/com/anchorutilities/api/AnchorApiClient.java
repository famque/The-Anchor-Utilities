package com.anchorutilities.api;

import com.anchorutilities.AnchorUtilitiesConfig;
import com.anchorutilities.model.Submission;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Sends submissions to the Apps Script web app. Uses RuneLite's own HTTP client and Gson. */
@Slf4j
@Singleton
public class AnchorApiClient
{
	/** Your Apps Script web app URL (ends in /exec). */
	private static final String ENDPOINT = "https://script.google.com/macros/s/AKfycbz-8EhNPuWESzpoapzyWmnbURjnzEpsc_r0YCR9Cy0mjP8xJXkTFfP3xkLbr-GVT1xP/exec";
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	@Inject private OkHttpClient http;
	@Inject private Gson gson;
	@Inject private AnchorUtilitiesConfig config;

	public boolean hasConnectCode()
	{
		return !connectCode().isEmpty();
	}

	/** Sends in the background; the callback runs when the script answers. */
	public void submit(Submission submission, Consumer<Submission.Result> callback)
	{
		submission.code = connectCode();
		Request request = new Request.Builder()
			.url(ENDPOINT)
			.post(RequestBody.create(JSON, gson.toJson(submission)))
			.build();

		// Apps Script answers with a redirect; OkHttp follows it automatically.
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Anchor submission failed", e);
				callback.accept(error("Could not reach the server"));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						callback.accept(error("Server error " + r.code()));
						return;
					}
					Submission.Result result = gson.fromJson(r.body().string(), Submission.Result.class);
					callback.accept(result != null ? result : error("Empty response"));
				}
				catch (IOException | RuntimeException e)
				{
					log.warn("Anchor submission response could not be read", e);
					callback.accept(error("Unreadable response"));
				}
			}
		});
	}

	private String connectCode()
	{
		String code = config.connectCode();
		return code == null ? "" : code.trim();
	}

	private static Submission.Result error(String message)
	{
		Submission.Result result = new Submission.Result();
		result.ok = false;
		result.error = message;
		return result;
	}
}