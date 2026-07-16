# Crono

Cronometro GPS per Android, pensato per sessioni in kartodromo e autodromo.

## Funzioni

- rilevamento automatico del primo giro e della linea di traguardo;
- tempi giro, miglior giro, ultimo giro e delta rispetto al miglior giro;
- intermedi automatici e intermedi aggiungibili o spostabili durante la registrazione;
- mappa OpenStreetMap, traccia GPS e modalità **Test GPS** per provare l'app da fermi;
- avvisi vocali in italiano con frequenza e livello di dettaglio configurabili;
- salvataggio della sessione e analisi finale;
- esportazione GPX delle registrazioni reali.

## Installazione

Scarica l'APK dalla pagina [Releases](../../releases), copialo sul telefono Android e aprilo. Android potrebbe chiedere di autorizzare l'installazione da questa origine.

L'app richiede la posizione precisa. Per una cronometrazione affidabile è consigliato usare il telefono con visuale libera del cielo.

## Nota

Crono è un progetto sperimentale e non sostituisce i sistemi di cronometraggio ufficiali. Usalo solo in contesti sicuri e nel rispetto delle regole del circuito.

## Sviluppo

Apri `legacy/native-prototype` in Android Studio, oppure esegui:

```powershell
gradle testDebugUnitTest assembleDebug
```
