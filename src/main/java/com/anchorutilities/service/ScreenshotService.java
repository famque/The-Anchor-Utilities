package com.anchorutilities.service;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.DrawManager;

/** Captures the game screen as a compressed JPEG for submission proof. */
@Slf4j
@Singleton
public class ScreenshotService
{
	/** Wider screenshots are scaled down to keep uploads small. */
	private static final int MAX_WIDTH = 1600;
	private static final float JPEG_QUALITY = 0.8f;

	@Inject private DrawManager drawManager;
	@Inject private ScheduledExecutorService executor;

	/**
	 * Captures the next rendered frame. The callback receives the image as base64,
	 * or null if the capture failed. It runs on a background thread.
	 */
	public void capture(Consumer<String> callback)
	{
		drawManager.requestNextFrameListener(image ->
			executor.execute(() ->
			{
				try
				{
					callback.accept(toBase64Jpeg(image));
				}
				catch (IOException | RuntimeException e)
				{
					log.warn("Anchor screenshot failed", e);
					callback.accept(null);
				}
			}));
	}

	private static String toBase64Jpeg(Image image) throws IOException
	{
		int width = image.getWidth(null);
		int height = image.getHeight(null);
		if (width <= 0 || height <= 0)
		{
			return null;
		}
		if (width > MAX_WIDTH)
		{
			height = height * MAX_WIDTH / width;
			width = MAX_WIDTH;
		}

		// JPEG has no transparency, so draw onto a plain RGB image first.
		BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = rgb.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(image, 0, 0, width, height, null);
		g.dispose();

		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
		if (!writers.hasNext())
		{
			return null;
		}
		ImageWriter writer = writers.next();
		ImageWriteParam param = writer.getDefaultWriteParam();
		param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		param.setCompressionQuality(JPEG_QUALITY);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes))
		{
			writer.setOutput(output);
			writer.write(null, new IIOImage(rgb, null, null), param);
		}
		finally
		{
			writer.dispose();
		}
		return Base64.getEncoder().encodeToString(bytes.toByteArray());
	}
}