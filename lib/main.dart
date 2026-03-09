import 'package:flutter/material.dart';
import 'package:gravador_tela/app/app_shell.dart';
import 'package:gravador_tela/features/editor/editor_controller.dart';
import 'package:provider/provider.dart';

import 'features/recorder/recording_controller.dart';
import 'features/gallery/gallery_controller.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [
        ChangeNotifierProvider(create: (_) => RecordingController()),
        ChangeNotifierProvider(create: (_) => GalleryController()),
        ChangeNotifierProvider(create: (_) => EditorController ()),
      ],
      child: MaterialApp(
        debugShowCheckedModeBanner: false,
        title: 'Gravador de Tela',
        theme: ThemeData(
          useMaterial3: true,
          brightness: Brightness.dark,
          primaryColor: Colors.red,
          scaffoldBackgroundColor: Colors.black,
          appBarTheme: const AppBarTheme(
            backgroundColor: Colors.transparent,
            foregroundColor: Colors.white,
            elevation: 0,
          ),
        ),
        home: const AppShell(),
      ),
    );
  }
}
