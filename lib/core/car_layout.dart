import 'dart:convert';

import 'package:flutter/services.dart';

/// The car home screen's widget layouts, one per screen shape ("wide" and "tall").
///
/// Saved by MainActivity under the prefs keys `dashmate_layout` (wide) and
/// `dashmate_layout_portrait` (tall) and read by the car page (drivedeck_home.html).
/// Besides the widgets, a layout carries page-wide options the car reads from it:
/// `clockFont` (cf-* class) and `clock24` (false = 12-hour clock).
class CarLayout {
  CarLayout._();

  static const MethodChannel _channel = MethodChannel('com.dashmate.dashmate/channel');

  /// Fonts the car page has for the clock (`body.cf-<id>`); 'thin' is the default.
  static const clockFonts = ['thin', 'orbitron', 'bebas', 'audiowide', 'monoton', 'teko', 'poiret'];

  static List<Map<String, dynamic>> defaultWidgets(bool tall) => tall
      ? [
          {'id': 'clock', 'x': 1, 'y': 1, 'w': 12, 'h': 3, 'text': 1.0, 'ta': 'left'},
          {'id': 'music', 'x': 1, 'y': 4, 'w': 12, 'h': 4, 'text': 1.0, 'ta': 'left'},
          {'id': 'prayer', 'x': 1, 'y': 8, 'w': 6, 'h': 3, 'text': 1.0, 'ta': 'left'},
          {'id': 'weather', 'x': 7, 'y': 8, 'w': 6, 'h': 3, 'text': 1.0, 'ta': 'left'},
          {'id': 'sports', 'x': 1, 'y': 11, 'w': 12, 'h': 2, 'text': 1.0, 'ta': 'left'},
          {'id': 'fuel', 'x': 1, 'y': 13, 'w': 12, 'h': 3, 'text': 1.0, 'ta': 'left'},
          {'id': 'speed', 'x': 1, 'y': 16, 'w': 5, 'h': 3, 'text': 1.0, 'ta': 'center'},
          {'id': 'shortcuts', 'x': 6, 'y': 16, 'w': 7, 'h': 3, 'text': 1.0, 'ta': 'center'},
        ]
      : [
          {'id': 'clock', 'x': 1, 'y': 1, 'w': 2, 'h': 2, 'text': 1.0, 'ta': 'left'},
          {'id': 'music', 'x': 1, 'y': 3, 'w': 7, 'h': 4, 'text': 1.0, 'ta': 'left'},
          {'id': 'weather', 'x': 9, 'y': 1, 'w': 4, 'h': 2, 'text': 1.0, 'ta': 'left'},
          {'id': 'prayer', 'x': 8, 'y': 3, 'w': 2, 'h': 2, 'text': 1.0, 'ta': 'left'},
          {'id': 'sports', 'x': 10, 'y': 3, 'w': 3, 'h': 2, 'text': 1.0, 'ta': 'left'},
          {'id': 'speed', 'x': 8, 'y': 5, 'w': 2, 'h': 2, 'text': 1.0, 'ta': 'center'},
          {'id': 'shortcuts', 'x': 10, 'y': 5, 'w': 3, 'h': 2, 'text': 1.0, 'ta': 'center'},
          {'id': 'fuel', 'x': 8, 'y': 7, 'w': 5, 'h': 2, 'text': 1.0, 'ta': 'left'},
        ];

  /// The saved layout for one screen shape, or null when none is saved / it can't be read.
  static Future<Map<String, dynamic>?> load(bool tall) async {
    try {
      final res = await _channel.invokeMethod('getLayoutSettings', {'tall': tall});
      if (res is! String || res.isEmpty) return null;
      final parsed = jsonDecode(res);
      return parsed is Map ? Map<String, dynamic>.from(parsed) : null;
    } catch (_) {
      return null;
    }
  }

  static Future<void> save(bool tall, Map<String, dynamic> layout) async {
    try {
      await _channel.invokeMethod('saveLayoutSettings', {'layout': jsonEncode(layout), 'tall': tall});
    } catch (_) {}
  }

  /// Sets page-wide options (e.g. `clockFont`, `clock24`) on both layouts, keeping the widgets.
  /// Saves run one after another, so two switches flipped quickly can't overwrite each other.
  static Future<void> _queue = Future.value();
  static Future<void> setOptions(Map<String, dynamic> options) =>
      _queue = _queue.then((_) => _setOptions(options)).catchError((_) {});

  static Future<void> _setOptions(Map<String, dynamic> options) async {
    for (final tall in [false, true]) {
      final current = await load(tall);
      final hasWidgets = current != null && current['widgets'] is List && (current['widgets'] as List).isNotEmpty;
      final layout = <String, dynamic>{
        'v': 2,
        'textScale': 1.0,
        'clockFont': 'thin',
        ...?current,
        if (!hasWidgets) 'widgets': defaultWidgets(tall),
        ...options,
      };
      await save(tall, layout);
    }
  }

  /// The current value of a page-wide option, from whichever layout has it.
  static Future<T?> option<T>(String key) async {
    for (final tall in [true, false]) {
      final v = (await load(tall))?[key];
      if (v is T) return v;
    }
    return null;
  }
}
