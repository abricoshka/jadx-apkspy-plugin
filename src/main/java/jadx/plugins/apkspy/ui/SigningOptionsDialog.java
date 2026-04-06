package jadx.plugins.apkspy.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import jadx.plugins.apkspy.ApkSignerWrapper;
import jadx.plugins.apkspy.ApkSpyOptions;
import jadx.plugins.apkspy.ApkSpyOptions.SigningConfig;
import jadx.plugins.apkspy.ApkSpyOptions.SigningMode;
import jadx.plugins.apkspy.ApkSpyOptions.SigningScheme;

public class SigningOptionsDialog extends JDialog {

	private final ApkSpyOptions options;
	private final JLabel debugKeystoreLabel;
	private final JRadioButton debugMode;
	private final JRadioButton customMode;
	private final JLabel debugKeystorePath;
	private final JTextField keystorePath;
	private final JButton browseKeystore;
	private final JTextField keyAlias;
	private final JPasswordField storePassword;
	private final JPasswordField keyPassword;
	private final JComboBox<SigningScheme> scheme;
	private final JTextField v1SignerName;

	public SigningOptionsDialog(JFrame mainWindow, ApkSpyOptions options) {
		super(SwingUtilities.windowForComponent(mainWindow), "Signing options", ModalityType.APPLICATION_MODAL);
		this.options = options;

		SigningConfig config = options.getSigningConfig();

		debugMode = new JRadioButton("Use debug/test key");
		customMode = new JRadioButton("Use custom keystore");
		ButtonGroup modeGroup = new ButtonGroup();
		modeGroup.add(debugMode);
		modeGroup.add(customMode);

		debugKeystoreLabel = new JLabel("Debug keystore:");
		debugKeystorePath = new JLabel(ApkSignerWrapper.resolveDefaultDebugKeystore().toAbsolutePath().toString());
		keystorePath = new JTextField(config.getKeystorePath(), 28);
		keyAlias = new JTextField(config.getKeyAlias(), 18);
		storePassword = new JPasswordField(config.getStorePassword(), 18);
		keyPassword = new JPasswordField(config.getKeyPassword(), 18);
		scheme = new JComboBox<>(SigningScheme.values());
		scheme.setSelectedItem(config.getScheme());
		v1SignerName = new JTextField(config.getV1SignerName(), 18);

		debugMode.setSelected(config.getMode() == SigningMode.DEBUG);
		customMode.setSelected(config.getMode() == SigningMode.CUSTOM);

		browseKeystore = new JButton("Browse...");
		browseKeystore.addActionListener(e -> chooseKeystore());

		debugMode.addActionListener(e -> refreshMode());
		customMode.addActionListener(e -> refreshMode());

		JPanel form = new JPanel(new GridBagLayout());
		form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(4, 4, 4, 4);
		gbc.anchor = GridBagConstraints.WEST;
		gbc.fill = GridBagConstraints.HORIZONTAL;

		int row = 0;
		addRow(form, gbc, row++, "Mode:", buildModePanel());
		addRow(form, gbc, row++, debugKeystoreLabel, debugKeystorePath);
		addRow(form, gbc, row++, "Keystore:", buildKeystorePanel());
		addRow(form, gbc, row++, "Alias:", keyAlias);
		addRow(form, gbc, row++, "Keystore password:", storePassword);
		addRow(form, gbc, row++, "Key password:", keyPassword);
		addRow(form, gbc, row++, "Scheme:", scheme);
		addRow(form, gbc, row++, "V1 signer name:", v1SignerName);

		JLabel note = new JLabel("Passwords are kept only for this session.");
		note.setBorder(BorderFactory.createEmptyBorder(0, 16, 8, 16));

		JButton save = new JButton("Save");
		save.addActionListener(e -> saveConfig());
		JButton cancel = new JButton("Cancel");
		cancel.addActionListener(e -> dispose());

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttons.add(save);
		buttons.add(cancel);

		add(form, BorderLayout.CENTER);
		add(note, BorderLayout.NORTH);
		add(buttons, BorderLayout.SOUTH);

		refreshMode();
		pack();
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		setLocationRelativeTo(mainWindow);
	}

	private JPanel buildModePanel() {
		JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		panel.add(debugMode);
		panel.add(new JLabel("   "));
		panel.add(customMode);
		return panel;
	}

	private JPanel buildKeystorePanel() {
		JPanel panel = new JPanel(new BorderLayout(6, 0));
		panel.add(keystorePath, BorderLayout.CENTER);
		panel.add(browseKeystore, BorderLayout.EAST);
		return panel;
	}

	private void addRow(JPanel panel, GridBagConstraints gbc, int row, String label, java.awt.Component component) {
		addRow(panel, gbc, row, new JLabel(label), component);
	}

	private void addRow(JPanel panel, GridBagConstraints gbc, int row, JLabel label, java.awt.Component component) {
		gbc.gridx = 0;
		gbc.gridy = row;
		gbc.weightx = 0;
		panel.add(label, gbc);

		gbc.gridx = 1;
		gbc.weightx = 1;
		panel.add(component, gbc);
	}

	private void chooseKeystore() {
		JFileChooser chooser = new JFileChooser();
		if (!keystorePath.getText().isBlank()) {
			chooser.setSelectedFile(new File(keystorePath.getText()));
		}
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
			keystorePath.setText(chooser.getSelectedFile().getAbsolutePath());
		}
	}

	private void refreshMode() {
		boolean useCustom = customMode.isSelected();
		debugKeystoreLabel.setVisible(!useCustom);
		debugKeystorePath.setVisible(!useCustom);
		keystorePath.setEnabled(useCustom);
		browseKeystore.setEnabled(useCustom);
		keyAlias.setEnabled(useCustom);
		storePassword.setEnabled(useCustom);
		keyPassword.setEnabled(useCustom);
		revalidate();
		repaint();
		pack();
	}

	private void saveConfig() {
		SigningMode mode = debugMode.isSelected() ? SigningMode.DEBUG : SigningMode.CUSTOM;
		if (mode == SigningMode.CUSTOM) {
			if (keystorePath.getText().isBlank()) {
				JOptionPane.showMessageDialog(this, "Choose a keystore path.", "apkSpy", JOptionPane.ERROR_MESSAGE);
				return;
			}
			if (keyAlias.getText().isBlank()) {
				JOptionPane.showMessageDialog(this, "Enter a key alias.", "apkSpy", JOptionPane.ERROR_MESSAGE);
				return;
			}
			if (storePassword.getPassword().length == 0) {
				JOptionPane.showMessageDialog(this, "Enter the keystore password.", "apkSpy", JOptionPane.ERROR_MESSAGE);
				return;
			}
		}
		options.updateSigningConfig(new SigningConfig(mode, keystorePath.getText(), keyAlias.getText(),
				(SigningScheme) scheme.getSelectedItem(), v1SignerName.getText(),
				new String(storePassword.getPassword()), new String(keyPassword.getPassword())));
		dispose();
	}
}
