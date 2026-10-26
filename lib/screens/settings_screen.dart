import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../core/car_layout.dart';
import '../theme/app_tokens.dart';
import '../theme/localization.dart';
import 'background_screen.dart';
import 'button_size_screen.dart';
import 'car_card_screen.dart';
import 'floating_menu_screen.dart';
import 'football_settings_screen.dart';
import 'prayer_screen.dart';
import 'setup_screen.dart';
import 'welcome_sound_screen.dart';
import 'widgets_screen.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  static const MethodChannel _channel = MethodChannel(
    'com.dashmate.dashmate/channel',
  );
  static const MethodChannel _browserChannel = MethodChannel(
    'drivedeck/car_browser',
  );

  String _bgMode = 'Cover';
  String _activeFont = 'Thin';
  final String _sound = 'Chime';
  double _buttonSize = 100.0;
  bool _is24Hour = true;
  bool _useKmh = true;
  bool _mediaButtonsOverride = true;

  AppTokens get t => context.tokens;

  @override
  void initState() {
    super.initState();
    _channel
        .invokeMethod<Map>('getUserSetup')
        .then((res) {
          final name = (res?['name'] as String?) ?? '';
          if (mounted) setState(() => _userName = name);
        })
        .catchError((_) {});
    _channel
        .invokeMethod<String>('appVersion')
        .then((v) {
          if (v != null && mounted) setState(() => _appVersion = v);
        })
        .catchError((_) {});
    _loadCarOptions();
  }

  /// The real build version (from pubspec via the native side), e.g. "1.0.9 (build 10)".
  String _appVersion = '';

  // "tall" (default) or "auto"; MainActivity keeps it in prefs (dashmate_layout_mode).
  String _layoutMode = 'tall';

  /// The owner's name from the first-run setup.
  String _userName = '';

  void _setLayoutMode(String mode) {
    setState(() => _layoutMode = mode);
    _channel
        .invokeMethod('setLayoutMode', {'mode': mode})
        .catchError((_) => null);
  }

  void _showLayoutModePicker() {
    showModalBottomSheet(
      context: context,
      backgroundColor: t.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(8, 16, 8, 8),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 12),
                child: Text(
                  'Car layout',
                  style: TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                    color: t.ink,
                  ),
                ),
              ),
              const SizedBox(height: 8),
              for (final o in const [
                [
                  'tall',
                  'Always tall',
                  'Uses the Tall widget placement and tall screens every time DashMate opens',
                ],
                [
                  'auto',
                  'Automatic',
                  'Tall on tall screens, Wide on wide screens',
                ],
              ])
                ListTile(
                  key: ValueKey('layout-mode-${o[0]}'),
                  title: Text(
                    o[1],
                    style: TextStyle(fontWeight: FontWeight.w600, color: t.ink),
                  ),
                  subtitle: Text(
                    o[2],
                    style: TextStyle(fontSize: 12, color: t.ink2),
                  ),
                  trailing: _layoutMode == o[0]
                      ? Icon(Icons.check, color: t.accent)
                      : null,
                  onTap: () {
                    Navigator.pop(ctx);
                    _setLayoutMode(o[0]);
                  },
                ),
            ],
          ),
        ),
      ),
    );
  }

  void _loadCarOptions() async {
    try {
      final mode = await _channel.invokeMethod<String>('getLayoutMode');
      if (mode != null && mounted)
        setState(() => _layoutMode = mode == 'auto' ? 'auto' : 'tall');
    } catch (_) {}
    final font = await CarLayout.option<String>('clockFont');
    final clock24 = await CarLayout.option<bool>('clock24');
    final speedMph = await CarLayout.option<bool>('speedMph');
    final mediaButtons = await CarLayout.option<bool>('mediaButtons');
    if (!mounted) return;
    setState(() {
      if (font != null && font.isNotEmpty)
        _activeFont = font[0].toUpperCase() + font.substring(1);
      if (clock24 != null) _is24Hour = clock24;
      if (speedMph != null) _useKmh = !speedMph;
      if (mediaButtons != null) _mediaButtonsOverride = mediaButtons;
    });
  }

  void _showFontPicker() {
    const fonts = CarLayout.clockFonts;
    showModalBottomSheet(
      context: context,
      backgroundColor: t.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (context) {
        return Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Clock & Speedometer Font',
                style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.bold,
                  color: t.ink,
                ),
              ),
              const SizedBox(height: 12),
              ...fonts.map(
                (f) => ListTile(
                  title: Text(
                    f.toUpperCase(),
                    style: TextStyle(color: t.ink, letterSpacing: 1.2),
                  ),
                  trailing: _activeFont.toLowerCase() == f
                      ? Icon(Icons.check, color: t.accent)
                      : null,
                  onTap: () {
                    setState(
                      () => _activeFont = f[0].toUpperCase() + f.substring(1),
                    );
                    // The car takes the clock font from the widget layouts (clockFont).
                    CarLayout.setOptions({'clockFont': f});
                    Navigator.pop(context);
                  },
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  void _clearBrowserCache() async {
    try {
      await _browserChannel.invokeMethod('clearBrowserData');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Browser data cleared / Data penyemak imbas dipadam'),
          ),
        );
      }
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: t.bg,
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        elevation: 0,
        title: const Text(
          'Settings',
          style: TextStyle(fontWeight: FontWeight.bold, letterSpacing: 0.5),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        children: [
          // Account card: tap to edit your name (and the car screen shape).
          InkWell(
            borderRadius: BorderRadius.circular(16),
            onTap: () async {
              final name = await Navigator.of(context).push<String>(
                MaterialPageRoute(builder: (_) => const SetupScreen()),
              );
              if (mounted && name != null) setState(() => _userName = name);
            },
            child: Container(
              padding: const EdgeInsets.all(18),
              decoration: BoxDecoration(
                color: t.surface,
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: t.border),
                boxShadow: t.cardShadow,
              ),
              child: Row(
                children: [
                  Container(
                    width: 48,
                    height: 48,
                    decoration: BoxDecoration(
                      color: t.accent,
                      shape: BoxShape.circle,
                    ),
                    child: Center(
                      child: Text(
                        _userName.isEmpty
                            ? '?'
                            : _userName.characters.first.toUpperCase(),
                        style: TextStyle(
                          fontSize: 22,
                          fontWeight: FontWeight.bold,
                          color: t.onAccent,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'DashMate Deck Pro',
                          style: TextStyle(
                            fontWeight: FontWeight.bold,
                            fontSize: 16,
                            color: t.ink,
                          ),
                        ),
                        SizedBox(height: 2),
                        Text(
                          '${_userName.isEmpty ? 'Owner' : _userName} · All features unlocked',
                          style: TextStyle(fontSize: 12, color: t.accent),
                        ),
                      ],
                    ),
                  ),
                  Icon(Icons.edit_outlined, size: 20, color: t.ink3),
                ],
              ),
            ),
          ),

          const SizedBox(height: 24),
          Text(
            'Car Settings',
            style: TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.bold,
              color: t.ink2,
            ),
          ),
          const SizedBox(height: 12),

          Container(
            decoration: BoxDecoration(
              color: t.surface,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: t.border),
            ),
            child: Column(
              children: [
                _buildSettingTile(
                  key: const ValueKey('car-layout-mode'),
                  icon: Icons.stay_current_portrait,
                  title: 'Car layout',
                  subtitle: _layoutMode == 'auto'
                      ? 'Automatic · follows the car screen\'s shape'
                      : 'Always tall · the tall widget placement every time',
                  onTap: _showLayoutModePicker,
                ),
                Divider(height: 1, indent: 56, color: t.border),
                _buildSettingTile(
                  icon: Icons.grid_view,
                  title: 'Car home widgets',
                  subtitle: '10 widgets available · Drag to reorder',
                  onTap: () => Navigator.push(
                    context,
                    MaterialPageRoute(builder: (_) => const WidgetsScreen()),
                  ),
                ),
                Divider(height: 1, indent: 56, color: t.border),
                _buildSettingTile(
                  icon: Icons.menu_open,
                  title: 'Car menu',
                  subtitle: '5 · shown in the floating menu',
                  onTap: () => Navigator.push(
                    context,
                    MaterialPageRoute(
                      builder: (_) => const FloatingMenuScreen(),
                    ),
                  ),
                ),
                Divider(height: 1, indent: 56, color: t.border),
                _buildSettingTile(
                  icon: Icons.wallpaper_outlined,
                  title: 'Car home background',
                  subtitle: _bgMode,
                  onTap: () => Navigator.push(
                    context,
                    MaterialPageRoute(builder: (_) => const BackgroundScreen()),
                  ),
                ),
                Divider(height: 1, indent: 56, color: t.border),
                _buildSettingTile(
                  icon: Icons.credit_card_outlined,
                  title: 'Car card (next to the map)',
                  subtitle: 'Auto · Music, Prayer, Weather, Sports',
                  onTap: () => Navigator.push(
                    context,
                    MaterialPageRoute(builder: (_) => const CarCardScreen()),
                  ),
                ),
                Divider(height: 1, indent: 56, color: t.border),
                _buildSettingTile(
                  icon: Icons.font_download_outlined,
                  title: 'Clock font',
                  subtitle: _activeFont,
                  onTap: _showFontPicker,
                ),
                Divider(height: 1, indent: 56, color: t.border),
                _buildSettingTile(
                  icon: Icons.graphic_eq,
                  title: 'Welcome sound',
                  subtitle: 'A chime when DashMate opens in the car · $_sound',
                  onTap: () => Navigator.push(
                    context,
                    MaterialPageRoute(
                      builder: (_) => const WelcomeSoundScreen(),
                    ),
                  ),
                ),
                Divider(height: 1, indent: 56, color: t.border),
                _buildSettingTile(
                  icon: Icons.touch_app_outlined,
                  title: 'Car button size',
                  subtitle:
                      '${_buttonSize.toInt()}% · Bigger car buttons, easier to tap while driving',
                  onTap: () => Navigator.push(
                    context,
                    MaterialPageRoute(builder: (_) => const ButtonSizeScreen()),
                  ),
                ),
                Divider(height: 1, indent: 56, color: t.border),
                Material(
                  type: MaterialType.transparency,
                  child: SwitchListTile(
                    secondary: Icon(Icons.access_time, color: t.ink3, size: 20),
                    title: Text(
                      '24-hour clock',
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w500,
                        color: t.ink,
                      ),
                    ),
                    subtitle: Text(
                      _is24Hour ? '13:00' : '1:00 PM',
                      style: TextStyle(fontSize: 12, color: t.ink2),
                    ),
                    value: _is24Hour,
                    onChanged: (val) {
                      setState(() => _is24Hour = val);
                      CarLayout.setOptions({'clock24': val});
                    },
                  ),
                ),
                Divider(height: 1, indent: 56, color: t.border),
                Material(
                  type: MaterialType.transparency,
                  child: SwitchListTile(
                    secondary: Icon(Icons.speed, color: t.ink3, size: 20),
                    title: Text(
                      'Speedometer unit',
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w500,
                        color: t.ink,
                      ),
                    ),
                    subtitle: Text(
                      _useKmh ? 'km/h' : 'mph',
                      style: TextStyle(fontSize: 12, color: t.ink2),
                    ),
                    value: _useKmh,
                    onChanged: (val) {
                      setState(() => _useKmh = val);
                      CarLayout.setOptions({'speedMph': !val});
                    },
                  ),
                ),
                Divider(height: 1, indent: 56, color: t.border),
                Material(
                  type: MaterialType.transparency,
                  child: SwitchListTile(
                    secondary: Icon(
                      Icons.radio_button_checked,
                      color: t.ink3,
                      size: 20,
                    ),
                    title: Text(
                      'Dashboard media buttons',
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w500,
                        color: t.ink,
                      ),
                    ),
                    subtitle: Text(
                      'Previous / play / next on the car\'s music card',
                      style: TextStyle(fontSize: 11, color: t.ink2),
                    ),
                    value: _mediaButtonsOverride,
                    onChanged: (val) {
                      setState(() => _mediaButtonsOverride = val);
                      CarLayout.setOptions({'mediaButtons': val});
                    },
                  ),
                ),
              ],
            ),
          ),

          const SizedBox(height: 24),
          Text(
            'Apps Settings',
            style: TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.bold,
              color: t.ink2,
            ),
          ),
          const SizedBox(height: 12),

          Container(
            decoration: BoxDecoration(
              color: t.surface,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: t.border),
            ),
            child: Column(
              children: [
                _buildSettingTile(
                  icon: Icons.mosque_outlined,
                  title: 'Prayer settings',
                  subtitle:
                      'Show prayer times, JAKIM zone, and Azan notifications',
                  onTap: () => Navigator.push(
                    context,
                    MaterialPageRoute(builder: (_) => const PrayerScreen()),
                  ),
                ),
                Divider(height: 1, indent: 56, color: t.border),
                _buildSettingTile(
                  icon: Icons.sports_soccer_outlined,
                  title: 'Football settings',
                  subtitle:
                      'Followed competitions, favourite teams, and API key',
                  isLast: true,
                  onTap: () => Navigator.push(
                    context,
                    MaterialPageRoute(
                      builder: (_) => const FootballSettingsScreen(),
                    ),
                  ),
                ),
              ],
            ),
          ),

          const SizedBox(height: 24),
          Text(
            'Permissions & Storage',
            style: TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.bold,
              color: t.ink2,
            ),
          ),
          const SizedBox(height: 12),

          Container(
            decoration: BoxDecoration(
              color: t.surface,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: t.border),
            ),
            child: Column(
              children: [
                _buildSettingTile(
                  icon: Icons.notifications_active_outlined,
                  title: 'Media Listener Access',
                  subtitle:
                      'Captures Spotify & YouTube Music artwork and live now playing',
                  onTap: () =>
                      _channel.invokeMethod('openNotificationSettings'),
                ),
                Divider(height: 1, indent: 56, color: t.border),
                _buildSettingTile(
                  icon: Icons.cleaning_services_outlined,
                  title: 'Clear browser data',
                  subtitle: 'Deletes cached pages, cookies and web storage',
                  onTap: _clearBrowserCache,
                ),
                Divider(height: 1, indent: 56, color: t.border),
                _buildSettingTile(
                  icon: Icons.language,
                  title: 'Language / Bahasa',
                  subtitle: AppLocalizations.isMs ? 'Bahasa Melayu' : 'English',
                  isLast: true,
                  onTap: () {
                    final next = AppLocalizations.isMs ? 'en' : 'ms';
                    AppLocalizations.setLocale(next);
                    _channel.invokeMethod('setLanguage', {'lang': next});
                    setState(() {});
                  },
                ),
              ],
            ),
          ),

          const SizedBox(height: 24),

          // Legal Disclaimers verbatim from AutoDeck
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: t.surface2,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: t.border),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(Icons.info_outline, size: 16, color: t.accent),
                    SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        'Legal Disclaimer / Penafian Undang-undang',
                        style: TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.bold,
                          color: t.ink,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 10),
                Text(
                  'DashMate is a personal, unofficial app. It is not affiliated with or endorsed by Google, Android Auto, Spotify, YouTube, Netflix, RTM, JAKIM, LRCLIB or football-data.org.\n\n'
                  'DashMate ialah aplikasi peribadi dan tidak rasmi. Ia tidak bergabung dengan atau disokong oleh Google, Android Auto, Spotify, YouTube, Netflix, RTM, JAKIM, LRCLIB atau football-data.org.\n\n'
                  'Prayer times, lyrics, weather and football data come from third-party sources and may be late, incomplete or wrong. Check official sources when it matters. Always obey traffic laws and never operate the screen while driving.',
                  style: TextStyle(fontSize: 10, color: t.ink3, height: 1.4),
                ),
              ],
            ),
          ),

          const SizedBox(height: 20),
          Center(
            child: Text(
              'DashMate Deck ${_appVersion.isEmpty ? '' : '$_appVersion '}(Personal Build)\nBuilt for Android Auto',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 11, color: t.ink3, height: 1.4),
            ),
          ),
          const SizedBox(height: 24),
        ],
      ),
    );
  }

  Widget _buildSettingTile({
    Key? key,
    required IconData icon,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
    bool isLast = false,
  }) {
    // Own (transparent) Material so the tap ripple shows on the white card.
    return Material(
      type: MaterialType.transparency,
      child: ListTile(
        key: key,
        onTap: onTap,
        leading: Icon(icon, color: t.ink3, size: 20),
        title: Text(
          title,
          style: TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w500,
            color: t.ink,
          ),
        ),
        subtitle: Text(subtitle, style: TextStyle(fontSize: 12, color: t.ink2)),
        trailing: Icon(Icons.arrow_forward_ios, size: 13, color: t.ink3),
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
      ),
    );
  }
}
