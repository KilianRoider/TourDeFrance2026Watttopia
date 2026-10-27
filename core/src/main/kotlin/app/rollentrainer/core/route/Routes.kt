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
            landmarks = listOf(Landmark(750.0, "Kehre 21 (806 m), Ende der ersten…"), Landmark(2300.0, "Kehre 17 (965 m)"), Landmark(2700.0, "La Garde-en-Oisans, Kehre 16 (995…"), Landmark(3400.0, "Kehre 14 (1055 m)…"), Landmark(4700.0, "Kehre 12 (1161 m), Ribot d'en Haut"), Landmark(7500.0, "Kehre 7 Dutch Corner, Kirche…"), Landmark(8600.0, "Dorf Huez (ca. 1450 m)"), Landmark(9000.0, "Steilster Kilometer beginnt (11,5…"), Landmark(11000.0, "Kehre 1 (1713 m), Einfahrt in den…"), Landmark(12000.0, "Vieil Alpe / Flamme rouge-Bereich")),
            hairpins = 21, biome = Biome.ALPINE,
        ),
        Route.fromKmGradients(
            id = "ventoux", name = "Mont Ventoux", region = "Provence",
            description = "Der Mont Ventoux von Bédoin ist der Klassiker unter den Ventoux-Auffahrten: Nach rund sechs gemäßigten Kilometern durch Weinberge beginnt am Virage de Saint-Estève ein fast zehn Kilometer langer Waldabschnitt, der kaum unter 9 Prozent fällt, bevor am Chalet Reynard die berühmte, kahle Kalkstein-Mondlandschaft des „Géant de Provence“ beginnt und der Wind zum Gegner wird. Der Schlussanstieg zum Observatorium zieht auf den letzten zwei Kilometern noch einmal auf rund 10 Prozent an. Die Tour de France befuhr den Berg erstmals 1951 und kehrte seitdem rund zwanzig Mal zurück, mit zehn Bergankünften auf dem Gipfel; der Gedenkstein kurz unterhalb des Gipfels erinnert an den tragischen Todesfall von 1967.",
            kind = RouteKind.CLIMB, startElevationM = 295.0, lengthKm = 21.80,
            kmGradients = listOf(1.9, 2.8, 3.8, 5.9, 5.7, 3.1, 8.7, 9.5, 10.6, 10.2, 9.4, 10.2, 9.3, 9.5, 8.9, 7.0, 6.7, 6.9, 7.5, 8.4, 9.2, 8.3),
            landmarks = listOf(Landmark(3500.0, "Sainte-Colombe"), Landmark(6000.0, "Virage de Saint-Estève (Eintritt…"), Landmark(10000.0, "Perrache (Mitte des Waldstücks)"), Landmark(15500.0, "Chalet Reynard (ca. 1419 m…"), Landmark(20300.0, "Tom-Simpson-Gedenkstein"), Landmark(20800.0, "Col des Tempêtes")),
            hairpins = 6, biome = Biome.VENTOUX,
        ),
        Route.fromKmGradients(
            id = "tourmalet", name = "Col du Tourmalet", region = "Pyrenäen",
            description = "Die Ostauffahrt zum Col du Tourmalet beginnt gemächlich durch das Tal von Gripp, wird ab Kilometer 4 aber zu einem unerbittlichen Anstieg von 8 bis 10 Prozent, der erst in den Lawinengalerien vor La Mongie seinen härtesten Abschnitt erreicht und bis zum Pass kaum nachlässt. Seit 1910 ist der Tourmalet der am häufigsten befahrene Pass der Tour de France und gilt als die Wiege der Pyrenäen-Legenden, inklusive der berühmten Schmiede-Reparatur von 1913 in Sainte-Marie-de-Campan. Mit 17 Kilometern und über 1260 Höhenmetern ist er ein echter Hors-Catégorie-Klassiker.",
            kind = RouteKind.CLIMB, startElevationM = 847.0, lengthKm = 17.00,
            kmGradients = listOf(3.9, 3.9, 3.5, 4.4, 6.9, 8.4, 7.9, 8.9, 8.4, 9.4, 8.9, 8.9, 9.4, 7.4, 8.4, 8.9, 9.4),
            landmarks = listOf(Landmark(4600.0, "Gripp - Ende des flachen Talstücks"), Landmark(6500.0, "Artigues"), Landmark(9300.0, "Lacet du Garet / Cascade du Garet"), Landmark(10700.0, "Lawinengalerie (ca. 250 m)"), Landmark(11700.0, "Graffiti-Galerie (ca. 300 m)…"), Landmark(12500.0, "La Mongie (Skistation, ca. 1740 m)"), Landmark(15000.0, "Letzte Serpentinen")),
            hairpins = 7, biome = Biome.PYRENEAN,
        ),
        Route.fromKmGradients(
            id = "galibier", name = "Col du Galibier", region = "Französische Alpen",
            description = "Von Valloire führt die Nordrampe des Galibier zunächst mit wechselnden, teils flachen Abschnitten durch das Tal der Valloirette bis Plan Lachat, wo die Straße die Talseite wechselt und auf den letzten acht Kilometern mit konstant 8 bis 10 Prozent über Kehren und karge Hochalpenlandschaft bis zur Passhöhe auf 2642 Metern ansteigt. Der Galibier ist seit 1911 Teil der Tour de France, wurde über 60 Mal befahren und war öfter als jeder andere Pass der höchste Punkt der Rundfahrt; 2011 gab es zum 100-jährigen Jubiläum erstmals eine Bergankunft auf dem Gipfel. Am Tunnelportal erinnert ein Denkmal an den Tour-Gründer Henri Desgrange, dem zu Ehren der Souvenir Henri Desgrange am höchsten Punkt der Tour vergeben wird.",
            kind = RouteKind.CLIMB, startElevationM = 1401.0, lengthKm = 18.10,
            kmGradients = listOf(7.0, 5.5, 3.0, 4.0, 6.0, 6.5, 7.5, 6.5, 6.0, 5.0, 9.0, 8.5, 8.5, 9.5, 7.0, 7.5, 8.0, 8.0, 10.0),
            landmarks = listOf(Landmark(2000.0, "Les Verneys, danach flacherer…"), Landmark(5000.0, "Bonnenuit (ca. 1670 m), letztes…"), Landmark(10000.0, "Plan Lachat (ca. 1970 m): Brücke…"), Landmark(14000.0, "Les Granges du Galibier (ca. 2300…"), Landmark(17000.0, "Abzweig Tunnel (2556 m…")),
            hairpins = 10, biome = Biome.ALPINE,
        ),
        Route.fromKmGradients(
            id = "planche", name = "La Super Planche des Belles Filles", region = "Vogesen",
            description = "Die Planche des Belles Filles ist ein kurzer, aber brutal unregelmäßiger Anstieg in den Vogesen: Rampen von 13 bis 14 Prozent wechseln sich mit kurzen Verschnaufpausen ab, bevor an der Skistation eine 20-Prozent-Rampe wartet. Seit 2019 geht es als \"Super Planche\" noch rund einen Kilometer auf Schotter weiter, mit Passagen bis 24 Prozent kurz vor dem Ziel. Die Tour de France kam seit 2012 sechsmal hierher (2012, 2014, 2017, 2019, 2020 als Bergzeitfahren, 2022), und fast jedes Mal behielt der Träger des Gelben Trikots nach dieser Bergankunft das Trikot bis Paris.",
            kind = RouteKind.CLIMB, startElevationM = 531.0, lengthKm = 7.00,
            kmGradients = listOf(9.6, 7.0, 9.8, 9.0, 6.9, 7.8, 10.8),
            landmarks = listOf(Landmark(300.0, "Erste Rampe bis ca. 14 %"), Landmark(1900.0, "Kurze Flachpassage / Erholung im…"), Landmark(3000.0, "Regelmäßige 12-%-Abschnitte im…"), Landmark(4900.0, "Kurve 4, Rampe über 13 %, danach…"), Landmark(5300.0, "Kurzes Gefälle vor dem Finale"), Landmark(5600.0, "20-%-Rampe zur Skistation…"), Landmark(5900.0, "Skistation / Le Resto, Parkplatz…"), Landmark(6100.0, "Ende des Asphalts, Beginn…"), Landmark(6600.0, "Schotterrampen bis 24 %"), Landmark(6900.0, "Letzte 100 m asphaltiert, Ziel…")),
            hairpins = 4, biome = Biome.PYRENEAN,
        ),
        Route.fromKmGradients(
            id = "izoard", name = "Col d'Izoard", region = "Französische Alpen",
            description = "Die Südauffahrt zum Col d'Izoard beginnt nach dem Abzweig bei Château-Queyras zunächst moderat durch Arvieux, bevor ab Brunissard eine lange, gnadenlose Gerade mit 9 bis 11 Prozent in den Lärchenwald und seine Kehren führt. Oben öffnet sich die Casse Déserte, eine karge Geröll- und Felsnadellandschaft, in der eine kurze Abfahrt die Beine kurz trügt, ehe die letzten zwei Kilometer mit rund 8 bis 9 Prozent zum Pass auf 2360 m ziehen. Seit 1922 über 35-mal in der Tour de France, hors catégorie, Schauplatz legendärer Bergduelle der 1950er Jahre und 2017 erstmals Bergankunft.",
            kind = RouteKind.CLIMB, startElevationM = 1370.0, lengthKm = 14.20,
            kmGradients = listOf(4.3, 6.3, 4.2, 5.9, 7.7, 6.8, 8.5, 9.0, 9.0, 8.6, 8.9, 4.0, 6.0, 8.2, 8.0),
            landmarks = listOf(Landmark(3700.0, "Arvieux (Dorf, Beginn der langen…"), Landmark(5500.0, "La Chalp"), Landmark(6400.0, "Brunissard – Beginn des steilsten…"), Landmark(7500.0, "Erste Kehren im Lärchenwald (Bois…"), Landmark(11400.0, "Col de la Platrière – Einfahrt in…"), Landmark(11800.0, "Casse Déserte (Mondlandschaft…"), Landmark(12200.0, "Stele Coppi-Bobet")),
            hairpins = 8, biome = Biome.ALPINE,
        ),
        Route.fromKmGradients(
            id = "hautacam", name = "Hautacam", region = "Pyrenäen",
            description = "Der Hautacam ist ein unregelmäßiger, launischer Pyrenäen-Anstieg: Statt gleichmäßiger Steigung wechseln sich steile Rampen von 10 bis 15 Prozent mit kurzen Flachstücken und sogar kleinen Gegengefällen ab, sodass man nie in einen Rhythmus findet. Der härteste Teil liegt zwischen dem siebten und elften Kilometer, bevor es über offenes Weideland zum ehemaligen Skigebiet auf 1520 m geht. Seit 1994 war die Bergankunft siebenmal Ziel einer Tour-de-France-Etappe, zuletzt 2022 und 2025, und entschied dabei mehrfach das Gesamtklassement.",
            kind = RouteKind.CLIMB, startElevationM = 465.0, lengthKm = 13.60,
            kmGradients = listOf(6.8, 6.3, 8.7, 5.3, 5.8, 7.3, 8.3, 10.0, 11.0, 7.3, 10.1, 8.3, 6.8, 5.8),
            landmarks = listOf(Landmark(1000.0, "Arbouix, Straße wird eng, erster…"), Landmark(3600.0, "Souin / Artalens-Souin, kurze…"), Landmark(7000.0, "Saint-André, Beginn des steilsten…"), Landmark(8000.0, "Les Chalets d'Assauts, steilste…"), Landmark(9500.0, "Letztes kurzes Flachstück, Wald…"), Landmark(11000.0, "Ferme de l'Arrioutou, Spitzkehre…"), Landmark(11600.0, "Viehgitter (passage canadien)…"), Landmark(12500.0, "Letzte vier Serpentinen vor dem…")),
            hairpins = 12, biome = Biome.PYRENEAN,
        ),
        Route.fromKmGradients(
            id = "madeleine", name = "Col de la Madeleine", region = "Französische Alpen",
            description = "Der Col de la Madeleine von La Chambre ist ein langer, erstaunlich gleichmäßiger Anstieg: Fast jeder Kilometer liegt zwischen 7 und 9 Prozent, echte Erholungsstücke gibt es nur am Ortseingang von Saint-François-Longchamp. Die ersten Kilometer durch Wald und Kehren oberhalb von Saint-Martin sind die härtesten, nach der Baumgrenze öffnet sich der Blick auf Mont Blanc und das Lauzière-Massiv. Seit der Premiere 1969 hat die Tour de France den Pass rund 28 Mal überquert, seit 1995 stets als Hors-Catégorie-Anstieg, zuletzt 2025 auf der Königsetappe zum Col de la Loze.",
            kind = RouteKind.CLIMB, startElevationM = 472.0, lengthKm = 19.20,
            kmGradients = listOf(6.9, 7.9, 8.9, 8.9, 8.9, 7.9, 7.9, 8.9, 7.9, 8.9, 7.9, 6.4, 7.7, 7.7, 8.1, 7.7, 6.9, 7.7, 7.4, 5.9),
            landmarks = listOf(Landmark(1500.0, "Saint-Martin-sur-la-Chambre…"), Landmark(3500.0, "Weiler La Côte, steile Rampen bis…"), Landmark(7900.0, "Lawinengalerie (Paravalanche)…"), Landmark(9500.0, "Le Planet"), Landmark(10500.0, "L'Epalud mit den Demoiselles…"), Landmark(11200.0, "Saint-François-Longchamp 1450…"), Landmark(13700.0, "Skistation…"), Landmark(15000.0, "Baumgrenze, offene Almwiesen bis…")),
            hairpins = 10, biome = Biome.ALPINE,
        ),
        Route.fromKmGradients(
            id = "puy-de-dome", name = "Puy de Dôme", region = "Auvergne",
            description = "Die Mautstraße auf den Puy de Dôme windet sich als Spirale ohne Serpentinen um den erloschenen Vulkankegel: Ab der Schranke sind es rund vier Kilometer mit praktisch konstant zwölf Prozent, kein Meter Erholung, und die Schlussrampe erreicht bis zu 17 Prozent. Die schmale, neben der Zahnradbahn verlaufende Straße ist für den Verkehr gesperrt und nur einmal im Jahr für Radfahrer offen. Die Tour de France kam zwischen 1952 und 1988 dreizehnmal hierher, darunter das legendäre Duell von 1964, und kehrte 2023 nach 35 Jahren mit einer Bergankunft ohne Zuschauer zurück.",
            kind = RouteKind.CLIMB, startElevationM = 935.0, lengthKm = 4.00,
            kmGradients = listOf(11.5, 12.0, 12.5, 12.0),
            landmarks = listOf(Landmark(1000.0, "Straße läuft neben der…"), Landmark(2500.0, "Kreuzung Sentier des Muletiers…"), Landmark(3200.0, "Großer Felsen 800 m vor dem Ziel"), Landmark(3800.0, "Letzte Kehre und Schlussrampe…")),
            hairpins = 2, biome = Biome.VOLCANIC,
        ),
        Route.fromKmGradients(
            id = "marie-blanque", name = "Col de Marie-Blanque", region = "Pyrenäen",
            description = "Die Ostauffahrt zum Col du Tourmalet beginnt gemächlich durch das Tal von Gripp, wird ab Kilometer 4 aber zu einem unerbittlichen Anstieg von 8 bis 10 Prozent, der erst in den Lawinengalerien vor La Mongie seinen härtesten Abschnitt erreicht und bis zum Pass kaum nachlässt. Seit 1910 ist der Tourmalet der am häufigsten befahrene Pass der Tour de France und gilt als die Wiege der Pyrenäen-Legenden, inklusive der berühmten Schmiede-Reparatur von 1913 in Sainte-Marie-de-Campan. Mit 17 Kilometern und über 1260 Höhenmetern ist er ein echter Hors-Catégorie-Klassiker.",
            kind = RouteKind.CLIMB, startElevationM = 847.0, lengthKm = 17.00,
            kmGradients = listOf(3.9, 3.9, 3.5, 4.4, 6.9, 8.4, 7.9, 8.9, 8.4, 9.4, 8.9, 8.9, 9.4, 7.4, 8.4, 8.9, 9.4),
            landmarks = listOf(Landmark(4600.0, "Gripp - Ende des flachen Talstücks"), Landmark(6500.0, "Artigues"), Landmark(9300.0, "Lacet du Garet / Cascade du Garet"), Landmark(10700.0, "Lawinengalerie (ca. 250 m)"), Landmark(11700.0, "Graffiti-Galerie (ca. 300 m)…"), Landmark(12500.0, "La Mongie (Skistation, ca. 1740 m)"), Landmark(15000.0, "Letzte Serpentinen")),
            hairpins = 4, biome = Biome.PYRENEAN,
        ),
    )

    fun byId(id: String): Route? = all.firstOrNull { it.id == id }
}
