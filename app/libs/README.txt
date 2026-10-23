Se o seu provedor entregar o glue do motor como um .aar/.jar
(em vez de só o .so), coloque aqui e troque em build.gradle.kts por:
  implementation(files("libs/tvbus-engine.aar"))
e remova as classes stub em com/tvbus/engine/.
