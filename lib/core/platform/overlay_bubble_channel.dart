import 'package:flutter/services.dart';

class OverlayBubbleChannel {
  static const _ch = MethodChannel('screen_recorder');

  static Future<bool> hasPermission() async =>
      await _ch.invokeMethod<bool>('hasOverlayPermission') ?? false;

  static Future<void> openSettings() async =>
      await _ch.invokeMethod('openOverlaySettings');

  static Future<void> show() async => await _ch.invokeMethod('showBubble');

  static Future<void> hide() async => await _ch.invokeMethod('hideBubble');
}