package com.anchorutilities.ui;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

public class AnchorPanel extends PluginPanel
{
	private final JLabel statusLabel = new JLabel("Not logged in");

	@Inject
	public AnchorPanel()
	{
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("The Anchor Utilities");
		title.setFont(FontManager.getRunescapeBoldFont());

		JPanel header = new JPanel(new GridLayout(2, 1));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.add(title);
		header.add(statusLabel);

		add(header, BorderLayout.NORTH);
	}

	public void setStatus(String status)
	{
		statusLabel.setText(status);
	}
}