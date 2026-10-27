package app.rollentrainer.core.route

/**
 * The built-in virtual roads: the famous climbs of the big tours, reproduced from public
 * kilometre-by-kilometre gradient profiles (climbbybike, cyclingcols, climbfinder and the like).
 * Elevations are rounded to the published figures; the road geometry around the profile is synthesised.
 */
object Routes {
    val all: List<Route> = listOf(
        Route.fromKmGradients(
            id = "alpe-dhuez", name = "Alpe d'Huez", region = "Französische Alpen",
            description = "Die Alpe d'Huez ist der Mythos der Tour de France: 13,8 km, 21 nummerierte Kehren und 8,1 % im Schnitt. Die ersten drei Kilometer bis La Garde sind mit rund 10 % am steilsten, danach pendelt die Steigung zwischen 8 und 9 %, ehe hinter dem Dorf Huez noch einmal ein Kilometer mit 11,5 % folgt und die letzten zwei Kilometer zum Ziel im Skiort flacher auslaufen. Seit der ersten Bergankunft 1952, der ersten der Tour-Geschichte überhaupt, endeten hier über 30 Etappen, und die Rekordzeit liegt unter 37 Minuten.",
            kind = RouteKind.CLIMB, startElevationM = 735.0, lengthKm = 13.80,
            kmGradients = listOf(10.4, 9.5, 8.8, 7.9, 8.3, 8.6, 8.0, 7.5, 8.0, 11.5, 9.0, 6.0, 5.0, 3.8),
            landmarks = listOf(Landmark(750.0, "Ende der ersten Rampe"), Landmark(2300.0, "Kehrenwand über dem Tal"), Landmark(2700.0, "La Garde-en-Oisans"), Landmark(3400.0, "Agostinho-Denkmal"), Landmark(4700.0, "Ribot d'en Haut"), Landmark(7500.0, "Dutch Corner · Saint-Ferréol"), Landmark(8600.0, "Huez (1450 m)"), Landmark(9000.0, "Steilster Kilometer (11,5 %)"), Landmark(11000.0, "Einfahrt in den Ort"), Landmark(12000.0, "Vieil Alpe · Flamme rouge")),
            hairpins = 21, biome = Biome.ALPINE,
            hairpinsAtKm = listOf(0.75, 1.14, 1.53, 1.91, 2.3, 2.7, 3.05, 3.4, 4.05, 4.7, 5.26, 5.82, 6.38, 6.94, 7.5, 8.08, 8.67, 9.25, 9.83, 10.42, 11.0),
        ),
        Route.fromKmGradients(
            id = "ventoux", name = "Mont Ventoux", region = "Provence",
            description = "Der Mont Ventoux von Bédoin ist der Klassiker unter den Ventoux-Auffahrten: Nach rund sechs gemäßigten Kilometern durch Weinberge beginnt am Virage de Saint-Estève ein fast zehn Kilometer langer Waldabschnitt, der kaum unter 9 Prozent fällt, bevor am Chalet Reynard die berühmte, kahle Kalkstein-Mondlandschaft des „Géant de Provence“ beginnt und der Wind zum Gegner wird. Der Schlussanstieg zum Observatorium zieht auf den letzten zwei Kilometern noch einmal auf rund 10 Prozent an. Die Tour de France befuhr den Berg erstmals 1951 und kehrte seitdem rund zwanzig Mal zurück, mit zehn Bergankünften auf dem Gipfel; der Gedenkstein kurz unterhalb des Gipfels erinnert an den tragischen Todesfall von 1967.",
            kind = RouteKind.CLIMB, startElevationM = 295.0, lengthKm = 21.80,
            kmGradients = listOf(1.9, 2.8, 3.8, 5.9, 5.7, 3.1, 8.7, 9.5, 10.6, 10.2, 9.4, 10.2, 9.3, 9.5, 8.9, 7.0, 6.7, 6.9, 7.5, 8.4, 9.2, 8.3),
            landmarks = listOf(Landmark(3500.0, "Sainte-Colombe"), Landmark(6000.0, "Saint-Estève · in den Wald"), Landmark(10000.0, "Mitten im Wald"), Landmark(15500.0, "Chalet Reynard (1419 m)"), Landmark(20300.0, "Simpson-Gedenkstein"), Landmark(20800.0, "Col des Tempêtes")),
            hairpins = 6, biome = Biome.VENTOUX,
        ),
        Route.fromKmGradients(
            id = "tourmalet", name = "Col du Tourmalet", region = "Pyrenäen",
            description = "Die Ostauffahrt zum Col du Tourmalet beginnt gemächlich durch das Tal von Gripp, wird ab Kilometer 4 aber zu einem unerbittlichen Anstieg von 8 bis 10 Prozent, der erst in den Lawinengalerien vor La Mongie seinen härtesten Abschnitt erreicht und bis zum Pass kaum nachlässt. Seit 1910 ist der Tourmalet der am häufigsten befahrene Pass der Tour de France und gilt als die Wiege der Pyrenäen-Legenden, inklusive der berühmten Schmiede-Reparatur von 1913 in Sainte-Marie-de-Campan. Mit 17 Kilometern und über 1260 Höhenmetern ist er ein echter Hors-Catégorie-Klassiker.",
            kind = RouteKind.CLIMB, startElevationM = 847.0, lengthKm = 17.00,
            kmGradients = listOf(3.9, 3.9, 3.5, 4.4, 6.9, 8.4, 7.9, 8.9, 8.4, 9.4, 8.9, 8.9, 9.4, 7.4, 8.4, 8.9, 9.4),
            landmarks = listOf(Landmark(4600.0, "Gripp · Ende des Tals"), Landmark(6500.0, "Artigues"), Landmark(9300.0, "Cascade du Garet"), Landmark(10700.0, "Lawinengalerie"), Landmark(11700.0, "Graffiti-Galerie · steilstes Stück"), Landmark(12500.0, "La Mongie (1740 m)"), Landmark(15000.0, "Letzte Serpentinen")),
            hairpins = 7, biome = Biome.PYRENEAN,
        ),
        Route.fromKmGradients(
            id = "galibier", name = "Col du Galibier", region = "Französische Alpen",
            description = "Von Valloire führt die Nordrampe des Galibier zunächst mit wechselnden, teils flachen Abschnitten durch das Tal der Valloirette bis Plan Lachat, wo die Straße die Talseite wechselt und auf den letzten acht Kilometern mit konstant 8 bis 10 Prozent über Kehren und karge Hochalpenlandschaft bis zur Passhöhe auf 2642 Metern ansteigt. Der Galibier ist seit 1911 Teil der Tour de France, wurde über 60 Mal befahren und war öfter als jeder andere Pass der höchste Punkt der Rundfahrt; 2011 gab es zum 100-jährigen Jubiläum erstmals eine Bergankunft auf dem Gipfel. Am Tunnelportal erinnert ein Denkmal an den Tour-Gründer Henri Desgrange, dem zu Ehren der Souvenir Henri Desgrange am höchsten Punkt der Tour vergeben wird.",
            kind = RouteKind.CLIMB, startElevationM = 1401.0, lengthKm = 18.10,
            kmGradients = listOf(7.0, 5.5, 3.0, 4.0, 6.0, 6.5, 7.5, 6.5, 6.0, 5.0, 9.0, 8.5, 8.5, 9.5, 7.0, 7.5, 8.0, 8.0, 10.0),
            landmarks = listOf(Landmark(2000.0, "Les Verneys"), Landmark(5000.0, "Bonnenuit (1670 m)"), Landmark(10000.0, "Plan Lachat · jetzt wird's steil"), Landmark(14000.0, "Les Granges du Galibier"), Landmark(17000.0, "Desgrange-Denkmal · Gipfelrampe")),
            hairpins = 10, biome = Biome.ALPINE,
        ),
        Route.fromKmGradients(
            id = "planche", name = "La Super Planche des Belles Filles", region = "Vogesen",
            description = "Die Planche des Belles Filles ist ein kurzer, aber brutal unregelmäßiger Anstieg in den Vogesen: Rampen von 13 bis 14 Prozent wechseln sich mit kurzen Verschnaufpausen ab, bevor an der Skistation eine 20-Prozent-Rampe wartet. Seit 2019 geht es als \"Super Planche\" noch rund einen Kilometer auf Schotter weiter, mit Passagen bis 24 Prozent kurz vor dem Ziel. Die Tour de France kam seit 2012 sechsmal hierher (2012, 2014, 2017, 2019, 2020 als Bergzeitfahren, 2022), und fast jedes Mal behielt der Träger des Gelben Trikots nach dieser Bergankunft das Trikot bis Paris.",
            kind = RouteKind.CLIMB, startElevationM = 531.0, lengthKm = 7.00,
            kmGradients = listOf(9.6, 7.0, 9.8, 9.0, 6.9, 7.8, 10.8),
            landmarks = listOf(Landmark(300.0, "Erste Rampe bis 14 %"), Landmark(1900.0, "Kurz Luft holen"), Landmark(3000.0, "12 % im Buchenwald"), Landmark(4900.0, "Rampe über 13 %"), Landmark(5300.0, "Kurzes Gefälle vor dem Finale"), Landmark(5600.0, "20 % zur Skistation"), Landmark(5900.0, "Alte Ziellinie (1035 m)"), Landmark(6100.0, "Schotter: Super Planche"), Landmark(6600.0, "Schotterrampen bis 24 %"), Landmark(6900.0, "Letzte 100 m")),
            hairpins = 4, biome = Biome.PYRENEAN,
        ),
        Route.fromKmGradients(
            id = "izoard", name = "Col d'Izoard", region = "Französische Alpen",
            description = "Die Südauffahrt zum Col d'Izoard beginnt nach dem Abzweig bei Château-Queyras zunächst moderat durch Arvieux, bevor ab Brunissard eine lange, gnadenlose Gerade mit 9 bis 11 Prozent in den Lärchenwald und seine Kehren führt. Oben öffnet sich die Casse Déserte, eine karge Geröll- und Felsnadellandschaft, in der eine kurze Abfahrt die Beine kurz trügt, ehe die letzten zwei Kilometer mit rund 8 bis 9 Prozent zum Pass auf 2360 m ziehen. Seit 1922 über 35-mal in der Tour de France, hors catégorie, Schauplatz legendärer Bergduelle der 1950er Jahre und 2017 erstmals Bergankunft.",
            kind = RouteKind.CLIMB, startElevationM = 1370.0, lengthKm = 14.20,
            kmGradients = listOf(4.3, 6.3, 4.2, 5.9, 7.7, 6.8, 8.5, 9.0, 9.0, 8.6, 8.9, 4.0, 6.0, 8.2, 8.0),
            landmarks = listOf(Landmark(3700.0, "Arvieux"), Landmark(5500.0, "La Chalp"), Landmark(6400.0, "Brunissard · steilster Teil"), Landmark(7500.0, "Kehren im Lärchenwald"), Landmark(11400.0, "Casse Déserte voraus"), Landmark(11800.0, "Casse Déserte"), Landmark(12200.0, "Coppi-Bobet-Stele")),
            hairpins = 8, biome = Biome.ALPINE,
        ),
        Route.fromKmGradients(
            id = "hautacam", name = "Hautacam", region = "Pyrenäen",
            description = "Der Hautacam ist ein unregelmäßiger, launischer Pyrenäen-Anstieg: Statt gleichmäßiger Steigung wechseln sich steile Rampen von 10 bis 15 Prozent mit kurzen Flachstücken und sogar kleinen Gegengefällen ab, sodass man nie in einen Rhythmus findet. Der härteste Teil liegt zwischen dem siebten und elften Kilometer, bevor es über offenes Weideland zum ehemaligen Skigebiet auf 1520 m geht. Seit 1994 war die Bergankunft siebenmal Ziel einer Tour-de-France-Etappe, zuletzt 2022 und 2025, und entschied dabei mehrfach das Gesamtklassement.",
            kind = RouteKind.CLIMB, startElevationM = 465.0, lengthKm = 13.60,
            kmGradients = listOf(6.8, 6.3, 8.7, 5.3, 5.8, 7.3, 8.3, 10.0, 11.0, 7.3, 10.1, 8.3, 6.8, 5.8),
            landmarks = listOf(Landmark(1000.0, "Arbouix"), Landmark(3600.0, "Artalens-Souin"), Landmark(7000.0, "Saint-André · bis 15 %"), Landmark(8000.0, "Steilste Kilometer"), Landmark(9500.0, "Letztes Flachstück"), Landmark(11000.0, "Spitzkehre Arrioutou"), Landmark(11600.0, "Weideland"), Landmark(12500.0, "Letzte vier Kehren")),
            hairpins = 12, biome = Biome.PYRENEAN,
        ),
        Route.fromKmGradients(
            id = "madeleine", name = "Col de la Madeleine", region = "Französische Alpen",
            description = "Der Col de la Madeleine von La Chambre ist ein langer, erstaunlich gleichmäßiger Anstieg: Fast jeder Kilometer liegt zwischen 7 und 9 Prozent, echte Erholungsstücke gibt es nur am Ortseingang von Saint-François-Longchamp. Die ersten Kilometer durch Wald und Kehren oberhalb von Saint-Martin sind die härtesten, nach der Baumgrenze öffnet sich der Blick auf Mont Blanc und das Lauzière-Massiv. Seit der Premiere 1969 hat die Tour de France den Pass rund 28 Mal überquert, seit 1995 stets als Hors-Catégorie-Anstieg, zuletzt 2025 auf der Königsetappe zum Col de la Loze.",
            kind = RouteKind.CLIMB, startElevationM = 472.0, lengthKm = 19.20,
            kmGradients = listOf(6.9, 7.9, 8.9, 8.9, 8.9, 7.9, 7.9, 8.9, 7.9, 8.9, 7.9, 6.4, 7.7, 7.7, 8.1, 7.7, 6.9, 7.7, 7.4, 5.9),
            landmarks = listOf(Landmark(1500.0, "Saint-Martin-sur-la-Chambre"), Landmark(3500.0, "La Côte · Rampen bis 11 %"), Landmark(7900.0, "Lawinengalerie"), Landmark(9500.0, "Le Planet"), Landmark(10500.0, "Demoiselles coiffées"), Landmark(11200.0, "Saint-François-Longchamp"), Landmark(13700.0, "Skistation (1650 m)"), Landmark(15000.0, "Baumgrenze")),
            hairpins = 10, biome = Biome.ALPINE,
        ),
        Route.fromKmGradients(
            id = "puy-de-dome", name = "Puy de Dôme", region = "Auvergne",
            description = "Die Mautstraße auf den Puy de Dôme windet sich als Spirale ohne Serpentinen um den erloschenen Vulkankegel: Ab der Schranke sind es rund vier Kilometer mit praktisch konstant zwölf Prozent, kein Meter Erholung, und die Schlussrampe erreicht bis zu 17 Prozent. Die schmale, neben der Zahnradbahn verlaufende Straße ist für den Verkehr gesperrt und nur einmal im Jahr für Radfahrer offen. Die Tour de France kam zwischen 1952 und 1988 dreizehnmal hierher, darunter das legendäre Duell von 1964, und kehrte 2023 nach 35 Jahren mit einer Bergankunft ohne Zuschauer zurück.",
            kind = RouteKind.CLIMB, startElevationM = 935.0, lengthKm = 4.00,
            kmGradients = listOf(11.5, 12.0, 12.5, 12.0),
            landmarks = listOf(Landmark(1000.0, "Neben der Zahnradbahn"), Landmark(2500.0, "Rampe bis 15 %"), Landmark(3200.0, "Noch 800 m"), Landmark(3800.0, "Schlussrampe bis 17 %")),
            hairpins = 2, biome = Biome.VOLCANIC,
        ),
        Route.fromKmGradients(
            id = "loze", name = "Col de la Loze", region = "Französische Alpen",
            description = "Der Col de la Loze ist der jüngste Gigant der Alpen: Ab Méribel führt ein erst 2019 asphaltierter, autofreier Radweg mit ständig wechselnder Steigung, Rampen von über 20 % und kurzen Gegenanstiegen bis auf 2304 m. Die Tour de France kam 2020 erstmals hierher, mit Bergankunft am höchsten Punkt der Rundfahrt, und überquerte den Pass 2023 erneut von Méribel aus, bevor es zum Altiport von Courchevel hinunterging. Die letzten sieben Kilometer gelten wegen ihrer unregelmäßigen Rampen als einer der härtesten Abschnitte im gesamten Tour-Kalender.",
            kind = RouteKind.CLIMB, startElevationM = 1435.0, lengthKm = 10.50,
            kmGradients = listOf(4.5, 7.1, 6.0, 7.7, 8.7, 7.2, 12.1, 11.0, 8.4, 8.8, 10.6),
            landmarks = listOf(Landmark(1600.0, "Kehren durch Méribel"), Landmark(3400.0, "Beginn des Radwegs zur Loze"), Landmark(4200.0, "Altiport Méribel (1700 m)"), Landmark(6000.0, "Drei steile Waldkehren"), Landmark(7800.0, "Rampe mit 20 % unter den Liften"), Landmark(8500.0, "Falscher Gipfel, noch 2 km"), Landmark(9500.0, "Noch 1 km"), Landmark(10200.0, "Letzte Rampe mit 20 %")),
            hairpins = 6, biome = Biome.ALPINE,
        ),
        Route.fromKmGradients(
            id = "portet", name = "Col du Portet", region = "Pyrenäen",
            description = "Der Col du Portet ist mit 2215 m der höchste Pass, den die Tour in den französischen Pyrenäen je befahren hat: 16 km bei 8,7 % ohne nennenswerte Erholung, die ersten sieben Kilometer teilt er sich mit dem Pla d'Adet bei 9 bis 11 %, bevor es in Espiaube rechts auf das schmale, raue Kehrensträßchen bis zum Tunnel und der über 10 % steilen Schlussrampe geht. Bereits 1982 geplant, aber kurzfristig gestrichen, feierte der Anstieg erst 2018 als Bergankunft einer 65 km kurzen Etappe seine Premiere und war 2021 erneut Ziel der 17. Etappe, beide Male mit entscheidenden Attacken der Gesamtwertungsfahrer.",
            kind = RouteKind.CLIMB, startElevationM = 822.0, lengthKm = 16.00,
            kmGradients = listOf(8.1, 10.4, 10.6, 9.7, 9.1, 7.9, 9.9, 5.9, 8.7, 9.1, 8.9, 8.7, 8.4, 6.2, 7.7, 10.3),
            landmarks = listOf(Landmark(1000.0, "Vignec · jetzt geht's los"), Landmark(5200.0, "Soulan · letzter Brunnen"), Landmark(7500.0, "Espiaube · kurz flach"), Landmark(10200.0, "Schäferhütte · Kehrenserie"), Landmark(13000.0, "Kurz Luft holen"), Landmark(15000.0, "Tunnel · Flamme rouge")),
            hairpins = 12, biome = Biome.PYRENEAN,
        ),
        Route.fromKmGradients(
            id = "mur-de-bretagne", name = "Mûr-de-Bretagne", region = "Bretagne",
            description = "Der Mûr-de-Bretagne, die \"Alpe d'Huez der Bretagne\", ist eine kurze, kerzengerade Wand: Die ersten 1000 m steigen mit rund 10 % an, danach flacht die Straße über eine lange Gerade bis zum Falschflach vor der Ziellinie im Dorf ab. Seit der Tour-Premiere 2011 war der Anstieg bei jeder Ankunft (2011, 2015, 2018, 2021, 2025) Schauplatz von Puncheur-Duellen, bei denen oft nur Sekunden entschieden; 2018, 2021 und 2025 wurde er im Finale zweimal befahren.",
            kind = RouteKind.CLIMB, startElevationM = 146.0, lengthKm = 2.00,
            kmGradients = listOf(9.8, 4.0),
            landmarks = listOf(Landmark(500.0, "Steilstes Stück geschafft"), Landmark(1000.0, "Lange Gerade"), Landmark(1500.0, "Ortseingang Mûr-de-Bretagne")),
            hairpins = 0, biome = Biome.FIELDS,
        ),
        Route.fromKmGradients(
            id = "aubisque", name = "Col d'Aubisque", region = "Pyrenäen",
            description = "Der Col d'Aubisque von Laruns ist ein Anstieg mit zwei Gesichtern: Bis Eaux-Bonnes rollt es im Wald mit 4 bis 5 Prozent moderat, danach wird es mit einer 13-Prozent-Rampe am Valentin-Wasserfall, Lawinengalerien und langen Passagen um 8 bis 9 Prozent bis über die Skistation Gourette hinaus richtig hart. Seit 1910, als die Tour de France erstmals die Hochpyrenäen überquerte, gehört der Pass mit über 70 Passagen zu den meistbefahrenen Bergen der Tour; Bergankünfte gab es 1985 und 2007, und 1951 stürzte hier ein Träger des Gelben Trikots in die Tiefe und überlebte.",
            kind = RouteKind.CLIMB, startElevationM = 519.0, lengthKm = 16.70,
            kmGradients = listOf(4.5, 4.8, 5.0, 5.0, 3.0, 6.0, 8.5, 8.5, 8.5, 7.5, 8.5, 8.5, 8.0, 9.0, 9.0, 9.0, 8.1),
            landmarks = listOf(Landmark(4000.0, "Eaux-Bonnes (710 m)"), Landmark(6300.0, "13 % nach der Brücke"), Landmark(7000.0, "Pont d'Iscoo"), Landmark(9400.0, "Pont de Goua"), Landmark(10600.0, "Lawinengalerien"), Landmark(12500.0, "Gourette (1348 m)"), Landmark(15000.0, "Crêtes Blanches · Aussicht"), Landmark(16300.0, "Letzte Kehre")),
            hairpins = 6, biome = Biome.PYRENEAN,
        ),
        Route.fromKmGradients(
            id = "luz-ardiden", name = "Luz Ardiden", region = "Pyrenäen",
            description = "Luz Ardiden ist eine 13 km lange Hors-Catégorie-Auffahrt von Luz-Saint-Sauveur zur Skistation auf 1700 m: nach zwei sanften Kilometern bis Sazos geht es ab Grust rund fünf Kilometer mit 8 bis 9 Prozent durch den Wald, bevor sich oberhalb der Baumgrenze eine Kette offener Serpentinen bis zum Ziel zieht. Seit der Premiere 1985 war der Berg neunmal Etappenziel der Tour de France, zuletzt 2021, und schrieb Tour-Geschichte durch Team-Intrigen, Soloflucht-Siege und einen Sturz am Fuß des Anstiegs, der die Rundfahrt 2003 fast gekippt hätte.",
            kind = RouteKind.CLIMB, startElevationM = 721.0, lengthKm = 13.10,
            kmGradients = listOf(5.1, 5.4, 7.4, 8.3, 8.8, 8.9, 9.0, 6.5, 7.8, 7.4, 8.0, 7.6, 7.4, 6.7),
            landmarks = listOf(Landmark(2700.0, "Sazos · jetzt wird's steil"), Landmark(4300.0, "Grust · 5 km mit 8–9 %"), Landmark(7500.0, "Kurz Luft holen"), Landmark(9000.0, "Raus aus dem Wald"), Landmark(11400.0, "Aulian · noch 1,7 km")),
            hairpins = 14, biome = Biome.PYRENEAN,
        ),
        Route.fromKmGradients(
            id = "joux-plane", name = "Col de Joux Plane", region = "Französische Alpen",
            description = "Der Col de Joux Plane ist der kompromissloseste Anstieg der Haute-Savoie: 11,6 km mit konstant 8,5 % und kaum einer Verschnaufpause, dazu ein 13-%-Stich in der Combe Emeru und ein zäher Waldabschnitt mit 9–10 % bis kurz vor den Gipfel. Seit 1978 hat die Tour de France den Pass 13-mal überquert, stets von Samoëns aus und immer mit der technischen Abfahrt nach Morzine als Finale, seit 1987 als Hors-Catégorie-Berg. Berühmt wurde er durch die Etappen von 2000, 2006, 2016 und 2023, auf denen sich hier regelmäßig die Gesamtwertung entschied.",
            kind = RouteKind.CLIMB, startElevationM = 698.0, lengthKm = 11.60,
            kmGradients = listOf(10.2, 8.4, 7.9, 6.8, 6.5, 8.5, 9.3, 9.8, 10.0, 9.2, 8.6, 6.7),
            landmarks = listOf(Landmark(1200.0, "Sous le Crêt"), Landmark(3200.0, "La Combe aux Flés"), Landmark(4500.0, "Kurz flach"), Landmark(5400.0, "Mévoutier · 6 km mit 9 %"), Landmark(6100.0, "Combe Emeru · 13 %"), Landmark(7100.0, "In den Wald"), Landmark(9600.0, "Blick auf den Mont Blanc")),
            hairpins = 6, biome = Biome.ALPINE,
        ),
        Route.fromKmGradients(
            id = "peyresourde", name = "Col de Peyresourde", region = "Pyrenäen",
            description = "Von Bagnères-de-Luchon zieht sich die D618 durch das Larboust-Tal hinauf. Die ersten Kilometer sind unregelmäßig, mit Kehren über dem Ort und einem Flachstück am Bach One. Nach Saint-Aventin und ab Castillon-de-Larboust bleibt es dann konstant um 8 % steil bis zu den weiten Schlusskehren unter dem Pass. Der Peyresourde stand 1910 erstmals im Programm der Tour de France und wurde seither weit über 50 Mal befahren, oft als Auftakt zu Bergankünften in Peyragudes, am Col du Portet oder in Saint-Lary-Soulan.",
            kind = RouteKind.CLIMB, startElevationM = 625.0, lengthKm = 13.70,
            kmGradients = listOf(7.0, 6.5, 3.5, 3.4, 8.1, 5.5, 6.6, 8.3, 7.6, 9.9, 7.4, 7.6, 8.0, 7.1),
            landmarks = listOf(Landmark(1000.0, "Kehren über Luchon"), Landmark(3000.0, "Flachstück am Bach"), Landmark(3800.0, "Kapelle Saint-Aventin"), Landmark(4000.0, "Abzweig Port de Balès"), Landmark(5200.0, "Saint-Aventin (926 m)"), Landmark(6500.0, "Castillon · ab jetzt 8 %"), Landmark(8000.0, "Garin (1114 m)"), Landmark(8900.0, "Noch 4,8 km mit 8 %"), Landmark(10700.0, "Schlusskehren")),
            hairpins = 6, biome = Biome.PYRENEAN,
        ),
        Route.fromKmGradients(
            id = "champs-elysees", name = "Champs-Élysées", region = "Paris",
            description = "Seit 1975 endet die Tour de France traditionell mit mehreren Runden auf diesem rund 6,8 km langen Stadtkurs: über grobes Kopfsteinpflaster hinauf zum Arc de Triomphe, der seit 2013 umrundet wird, hinunter zur Place de la Concorde, durch den Tunnel unter den Tuilerien und über die Rue de Rivoli zurück auf die Prachtstraße. Die Runde ist im Grunde flach, nur der gut 1,5 km lange, sanfte Anstieg zum Triumphbogen (meist 1 bis 1,5 %, kurz gut 3 %) und die kurze Rampe aus dem Tuilerien-Tunnel brechen den Rhythmus. Hier fällt meist der prestigeträchtigste Massensprint des Jahres, 1989 entschied auf den Champs-Élysées sogar ein Zeitfahren die Tour mit nur acht Sekunden Vorsprung.",
            kind = RouteKind.FLAT, startElevationM = 37.0, lengthKm = 6.80,
            kmGradients = listOf(0.9, 0.5, -1.2, -0.5, -0.1, 0.2, 0.2),
            landmarks = listOf(Landmark(300.0, "Rond-Point des Champs-Élysées"), Landmark(1100.0, "Avenue George V"), Landmark(1500.0, "Place de l'Étoile"), Landmark(1700.0, "Arc de Triomphe"), Landmark(2000.0, "Zurück über die Champs-Élysées"), Landmark(3400.0, "Ziellinie"), Landmark(3900.0, "Place de la Concorde"), Landmark(4200.0, "Tunnel unter den Tuilerien"), Landmark(4600.0, "Tiefster Punkt"), Landmark(5000.0, "Rampe am Louvre"), Landmark(5200.0, "Rue de Rivoli"), Landmark(6100.0, "Concorde · zurück auf die Champs")),
            hairpins = 0, biome = Biome.CITY,
        ),
        Route.fromKmGradients(
            id = "marie-blanque", name = "Col de Marie-Blanque", region = "Pyrenäen",
            description = "Die Westrampe von Escot beginnt täuschend sanft: Rund vier Kilometer rollt die schmale D294 mit 3-7 % durch das enge Tal des Barescou, bevor sie im Wald fast schnurgerade und ohne Erholung mit 10-13 % zum Pass hinaufzieht, erst eine einzige Kehre kurz vor dem Gipfel kündigt das Ende an. Seit seiner Tour-Premiere 1978 hat der Pass zwischen Aspe- und Ossau-Tal mehr als ein Dutzend Etappen geprägt, zuletzt 2020 und 2023 jeweils als letzter großer Anstieg vor der Abfahrt nach Laruns.",
            kind = RouteKind.CLIMB, startElevationM = 330.0, lengthKm = 9.20,
            kmGradients = listOf(2.5, 3.5, 5.0, 6.5, 7.5, 11.0, 10.5, 12.0, 11.5, 2.5),
            landmarks = listOf(Landmark(2300.0, "Steinbrücke am Barescou"), Landmark(4000.0, "In den Wald"), Landmark(5000.0, "Ab jetzt zweistellig"), Landmark(5500.0, "3 km mit 11–13 %"), Landmark(7500.0, "Steilstes Stück (13 %)"), Landmark(8900.0, "Die einzige Kehre")),
            hairpins = 1, biome = Biome.PYRENEAN,
            hairpinsAtKm = listOf(8.9),
        ),
    )

    fun byId(id: String): Route? = all.firstOrNull { it.id == id }
}
