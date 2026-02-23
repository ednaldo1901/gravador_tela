import 'package:flutter/material.dart';
import 'package:gravador_tela/app/app_shell.dart';
import 'package:provider/provider.dart';

import 'features/recorder/recording_controller.dart';
import 'features/gallery/gallery_controller.dart';

void main() => runApp(const MyApp());

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [
        ChangeNotifierProvider(create: (_) => RecordingController()),
        ChangeNotifierProvider(create: (_) => GalleryController()),
      ],
      child: MaterialApp(
        debugShowCheckedModeBanner: false,
        theme: ThemeData(useMaterial3: true),
        home: const AppShell(),
      ),
    );
  }
}
  