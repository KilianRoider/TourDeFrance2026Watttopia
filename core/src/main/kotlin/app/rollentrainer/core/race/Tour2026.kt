package app.rollentrainer.core.race

/**
 * The field of the 2026 Tour de France: 23 teams of eight, 184 riders, as they started in Barcelona
 * (start list of 1 July 2026). Weights and power profiles are estimates: the famous riders from public
 * reporting of their best climbs, sprints and time trials, the others from their role and level. Kit
 * colours are approximate (no logos). One text line per rider below, so a corrected start list or a
 * better estimate is a one-line edit.
 */
object Tour2026 {
    val teams: List<Team> = listOf(
        Team(0, "UAE", "UAE Team Emirates XRG", 0xF4F4F4, 0x111111, 0xC8102E),
        Team(1, "TVL", "Team Visma | Lease a Bike", 0xFFD400, 0x141414, 0xFFFFFF),
        Team(2, "RBH", "Red Bull – BORA – hansgrohe", 0xF4F4F4, 0x1B2A6B, 0xD6001C),
        Team(3, "LTK", "Lidl-Trek", 0x1F4FA0, 0x0A2240, 0xE30613),
        Team(4, "EFE", "EF Education – EasyPost", 0xFF5CA8, 0x1D2A4D, 0xFFFFFF),
        Team(5, "DCT", "Decathlon CMA CGM", 0x2EC4C6, 0x0B3A5B, 0xE2231A),
        Team(6, "XAT", "XDS Astana Team", 0x3AA8DD, 0x0B2545, 0xF2C500),
        Team(7, "TBV", "Bahrain Victorious", 0x0B1F45, 0x7EC8E3, 0xD0102A),
        Team(8, "NIN", "Netcompany INEOS", 0xFF6A13, 0xE6E6E6, 0x1A1A1A),
        Team(9, "SOQ", "Soudal Quick-Step", 0x0055A5, 0xFFFFFF, 0xC6FF00),
        Team(10, "APT", "Alpecin – Premier Tech", 0x1C3F94, 0x0A1A3A, 0xE4002B),
        Team(11, "JAY", "Team Jayco AlUla", 0x5B2A86, 0x1B1340, 0xFF7A00),
        Team(12, "UXM", "Uno-X Mobility", 0xD7141A, 0x7A0A0E, 0xFFD100),
        Team(13, "NSN", "NSN Cycling Team", 0x6EC1E4, 0x12304F, 0xF68B1F),
        Team(14, "MOV", "Movistar Team", 0xF7F7F7, 0x0A2240, 0x4FC3E8),
        Team(15, "LOI", "Lotto Intermarché", 0xD71920, 0xFFFFFF, 0x1F3A93),
        Team(16, "COF", "Cofidis", 0xD7141A, 0xFFFFFF, 0x1A1A1A),
        Team(17, "PQT", "Pinarello – Q36.5", 0x1A1A1A, 0x9EA4AA, 0xFFFFFF),
        Team(18, "GFC", "Groupama – FDJ United", 0x6FA8DC, 0x0B2A5B, 0xE30613),
        Team(19, "TUD", "Tudor Pro Cycling", 0xE30613, 0xFFFFFF, 0x111111),
        Team(20, "TTE", "Team TotalEnergies", 0x1A4C9C, 0xFFFFFF, 0xF39200),
        Team(21, "TPP", "Team Picnic PostNL", 0x202020, 0xFFFFFF, 0xFF6600),
        Team(22, "CJR", "Caja Rural – Seguros RGA", 0x00843D, 0xFFFFFF, 0x0B3D20),
    )

    /** bib|name|nation|team|role|kg|ftp W|20 s|1 min|5 min|20 min (W/kg); parsed once, keeps the class small. */
    private const val DATA0 = """
1|Tadej Pogačar|SLO|0|GC|66|435|15.0|10.6|7.90|7.00
2|Isaac del Toro|MEX|0|GC|64|403|14.8|10.3|7.50|6.65
3|Felix Großschartner|AUT|0|DOM|66|380|12.9|9.0|6.72|6.07
4|Brandon McNulty|USA|0|TT|70|413|13.5|9.4|6.90|6.20
5|Nils Politt|GER|0|ROU|80|424|13.8|8.4|6.23|5.57
6|Florian Vermeersch|BEL|0|ROU|78|402|13.1|8.4|5.94|5.41
7|Tim Wellens|BEL|0|PUN|70|392|14.6|9.6|6.81|5.94
8|Adam Yates|GBR|0|CLI|58|354|13.0|9.3|7.20|6.45
11|Jonas Vingegaard|DEN|1|GC|60|402|13.6|9.9|7.90|7.05
12|Edoardo Affini|ITA|1|TT|82|426|11.4|7.7|5.80|5.43
13|Bruno Armirail|FRA|1|ROU|70|378|13.3|8.5|6.32|5.67
14|Victor Campenaerts|BEL|1|TT|72|396|12.5|8.4|6.22|5.75
15|Per Strand Hagenes|NOR|1|PUN|72|389|13.6|9.4|6.51|5.73
16|Matteo Jorgenson|USA|1|GC|69|421|14.0|9.6|7.00|6.45
17|Sepp Kuss|USA|1|CLI|61|372|12.8|9.2|7.20|6.45
18|Davide Piganzoli|ITA|1|CLI|64|371|12.0|8.9|6.80|6.12
21|Remco Evenepoel|BEL|2|GC|61|403|14.2|10.0|7.60|6.95
22|Mattia Cattaneo|ITA|2|DOM|66|376|12.8|8.9|6.53|6.01
23|Nico Denz|GER|2|ROU|75|390|13.1|8.7|5.98|5.46
24|Jai Hindley|AUS|2|CLI|60|360|12.8|9.6|7.17|6.33
25|Florian Lipowitz|GER|2|GC|64|410|13.0|9.5|7.40|6.75
26|Jan Tratnik|SLO|2|ROU|73|394|13.6|8.5|6.35|5.67
27|Tim van Dijke|NED|2|ROU|78|406|13.5|8.5|6.08|5.47
28|Maxim Van Gils|BEL|2|PUN|62|360|15.2|10.3|7.07|6.15
31|Juan Ayuso|ESP|3|GC|65|410|14.0|9.7|7.30|6.65
32|Derek Gee-West|CAN|3|CLI|72|425|12.4|9.1|6.89|6.23
33|Mads Pedersen|DEN|3|SPR|74|414|18.0|12.2|6.70|5.95
34|Quinn Simmons|USA|3|ROU|73|401|14.2|8.9|6.36|5.77
35|Mattias Skjelmose|DEN|3|PUN|64|384|15.0|10.6|7.20|6.35
36|Toms Skujiņš|LAT|3|PUN|66|363|14.7|9.5|6.70|5.83
37|Mathias Vacek|CZE|3|ROU|76|418|13.8|9.1|6.41|5.78
38|Carlos Verona|ESP|3|DOM|64|358|12.4|8.8|6.42|5.90
41|Richard Carapaz|ECU|4|CLI|62|378|14.5|10.0|7.20|6.45
42|Kasper Asgreen|DEN|4|ROU|75|405|14.2|8.6|6.34|5.67
43|Alex Baudin|FRA|4|CLI|62|353|12.1|8.8|6.71|6.01
44|Ben Healy|IRL|4|PUN|65|390|14.5|10.5|7.10|6.35
45|Sean Quinn|USA|4|PUN|70|371|13.9|9.3|6.46|5.62
46|Georg Steinhauser|GER|4|CLI|64|371|12.0|8.8|6.85|6.12
47|Michael Valgren|DEN|4|PUN|72|389|14.6|9.5|6.60|5.73
48|Max Walker|GBR|4|DOM|68|360|11.8|8.3|6.06|5.59
51|Paul Seixas|FRA|5|GC|65|410|14.0|9.8|7.40|6.65
52|Tiesj Benoot|BEL|5|PUN|72|396|14.2|9.9|6.62|5.83
53|Cees Bol|NED|5|ROU|83|415|16.5|11.0|6.00|5.25
54|Daan Hoole|NED|5|TT|80|416|11.7|7.9|5.90|5.43
55|Olav Kooij|NED|5|SPR|71|369|19.5|12.8|6.20|5.50
56|Aurélien Paret-Peintre|FRA|5|CLI|60|348|11.9|8.9|6.88|6.12
"""

    private const val DATA1 = """
57|Nicolas Prodhomme|FRA|5|DOM|66|363|12.3|8.8|6.39|5.80
58|Matthew Riccitello|USA|5|CLI|60|348|12.1|8.9|6.89|6.12
61|Sergio Higuita|COL|6|CLI|59|348|12.5|9.2|6.94|6.22
62|Davide Ballerini|ITA|6|ROU|75|383|16.0|11.0|6.00|5.35
63|Aaron Gate|NZL|6|ROU|75|398|13.1|8.5|6.12|5.57
64|Max Kanter|GER|6|SPR|77|385|18.5|12.0|5.90|5.25
65|Harold Tejada|COL|6|CLI|62|347|11.8|8.5|6.64|5.90
66|Mike Teunissen|NED|6|ROU|73|380|15.5|10.6|6.00|5.45
67|Simone Velasco|ITA|6|PUN|64|352|14.3|9.6|6.78|5.83
68|Nicolas Vinokurov|KAZ|6|ROU|70|364|13.4|8.5|6.00|5.46
71|Lenny Martinez|FRA|7|CLI|52|317|13.2|9.5|7.30|6.45
72|Phil Bauhaus|GER|7|SPR|77|385|19.0|12.3|5.90|5.25
73|Damiano Caruso|ITA|7|CLI|67|389|12.1|8.9|6.82|6.13
74|Kamil Gradek|POL|7|ROU|77|400|13.4|8.7|6.11|5.45
75|Robert Stannard|AUS|7|PUN|72|389|14.4|9.2|6.63|5.73
76|Matej Mohorič|SLO|7|PUN|71|391|13.9|9.4|6.73|5.84
77|Antonio Tiberi|ITA|7|GC|66|403|13.6|9.6|7.10|6.45
78|Vlad Van Mechelen|BEL|7|ROU|75|383|15.5|10.6|6.00|5.35
81|Egan Bernal|COL|8|GC|61|372|13.2|9.5|7.10|6.45
82|Thymen Arensman|NED|8|GC|68|415|13.0|9.3|7.10|6.45
83|Tobias Foss|NOR|8|TT|72|403|12.3|8.3|6.23|5.85
84|Filippo Ganna|ITA|8|TT|83|456|16.0|10.0|6.40|5.80
85|Dorian Godon|FRA|8|PUN|68|367|16.5|11.6|6.60|5.70
86|Michał Kwiatkowski|POL|8|PUN|68|374|13.9|9.7|6.81|5.83
87|Joshua Tarling|GBR|8|TT|78|429|12.5|8.0|6.28|5.75
88|Kévin Vauquelin|FRA|8|GC|64|378|14.5|10.3|7.10|6.25
91|Tim Merlier|BEL|9|SPR|76|380|20.0|12.6|5.90|5.30
92|Pascal Eenkhoorn|NED|9|ROU|72|382|13.2|8.8|6.14|5.57
93|Valentin Paret-Peintre|FRA|9|CLI|58|342|11.7|9.0|7.04|6.22
94|Jasper Stuyven|BEL|9|ROU|78|413|15.5|11.0|6.20|5.55
95|Dylan van Baarle|NED|9|ROU|78|413|13.8|8.4|6.21|5.56
96|Bert Van Lerberghe|BEL|9|ROU|77|385|15.5|10.6|6.00|5.30
97|Ilan Van Wilder|BEL|9|DOM|66|383|13.3|9.0|6.81|6.12
98|Louis Vervaeke|BEL|9|DOM|66|363|12.4|8.7|6.37|5.80
101|Mathieu van der Poel|NED|10|PUN|75|435|19.0|12.6|7.00|6.15
102|Ramses Debruyne|BEL|10|ROU|72|367|12.7|8.5|5.98|5.35
103|Silvan Dillier|SUI|10|ROU|73|380|13.6|8.2|6.00|5.47
104|Tim Marsman|NED|10|ROU|73|380|12.8|8.6|5.96|5.47
105|Jasper Philipsen|BEL|10|SPR|75|375|20.5|13.0|6.00|5.30
106|Edward Planckaert|BEL|10|ROU|78|390|12.4|8.1|5.82|5.25
107|Jonas Rickaert|BEL|10|ROU|78|398|15.5|10.6|6.00|5.40
108|Emiel Verstrynge|BEL|10|PUN|68|360|13.8|9.2|6.40|5.61
111|Ben O'Connor|AUS|11|GC|60|360|12.6|9.0|6.80|6.30
112|Pascal Ackermann|GER|11|SPR|78|390|19.0|12.3|5.90|5.25
113|Luke Durbridge|AUS|11|ROU|80|416|13.2|8.6|6.00|5.46
114|Felix Engelhardt|GER|11|PUN|68|367|14.6|9.6|6.59|5.72
"""

    private const val DATA2 = """
115|Michael Matthews|AUS|11|PUN|72|396|17.5|12.0|6.70|5.85
116|Kelland O'Brien|AUS|11|ROU|75|390|13.6|8.4|6.08|5.46
117|Luke Plapp|AUS|11|TT|70|406|13.3|8.8|6.58|6.06
118|Mauro Schmid|SUI|11|PUN|68|374|14.5|9.6|6.69|5.83
121|Tobias Halland Johannessen|NOR|12|GC|62|372|13.4|9.6|7.00|6.35
122|Jonas Abrahamsen|NOR|12|ROU|73|394|14.0|9.0|6.35|5.67
123|Anthon Charmig|DEN|12|CLI|60|336|11.9|8.9|6.51|5.91
124|Magnus Cort|DEN|12|PUN|71|383|17.0|11.6|6.40|5.70
125|Anders Halland Johannessen|NOR|12|CLI|62|347|11.2|8.7|6.60|5.90
126|Anders Skaarseth|NOR|12|DOM|66|356|12.2|8.5|6.25|5.69
127|Torstein Træen|NOR|12|CLI|64|365|11.9|8.8|6.75|6.02
128|Søren Wærenskjold|NOR|12|SPR|85|425|18.0|11.5|5.90|5.25
131|Biniam Girmay|ERI|13|SPR|70|371|19.0|13.0|6.50|5.60
132|Lewis Askey|GBR|13|ROU|72|367|13.4|8.1|5.88|5.35
133|George Bennett|NZL|13|CLI|58|325|11.2|8.9|6.52|5.91
134|Marco Frigo|ITA|13|CLI|64|358|11.8|8.9|6.58|5.90
135|Matīs Louvel|FRA|13|ROU|72|374|13.1|8.3|6.07|5.45
136|Krists Neilands|LAT|13|PUN|66|356|14.6|9.6|6.66|5.72
137|Jake Stewart|GBR|13|ROU|70|357|15.5|10.8|6.00|5.35
138|Tom Van Asbroeck|BEL|13|ROU|73|365|15.0|10.5|5.90|5.30
141|Cian Uijtdebroeks|BEL|14|GC|63|378|12.8|9.2|7.00|6.35
142|Pablo Castrillo|ESP|14|CLI|66|370|11.3|8.6|6.67|5.91
143|Jefferson Alveiro Cepeda|ECU|14|CLI|58|331|11.3|8.8|6.81|6.02
144|Raúl García Pierna|ESP|14|PUN|66|363|14.7|9.5|6.66|5.83
145|Michel Heßmann|GER|14|ROU|72|382|13.8|8.8|6.23|5.57
146|Nelson Oliveira|POR|14|TT|70|371|12.4|7.9|6.02|5.54
147|Javier Romo|ESP|14|DOM|66|370|13.0|8.8|6.45|5.91
148|Einer Rubio|COL|14|CLI|58|331|12.0|9.0|6.66|6.02
151|Arnaud De Lie|BEL|15|SPR|78|398|18.8|13.0|6.30|5.40
152|Huub Artz|NED|15|CLI|66|370|11.7|8.5|6.71|5.91
153|Jenno Berckmoes|BEL|15|PUN|66|356|14.1|9.5|6.52|5.72
154|Lars Craps|BEL|15|ROU|74|370|13.0|8.2|5.85|5.25
155|Liam Slock|BEL|15|ROU|74|377|15.0|10.5|5.90|5.30
156|Lennert Van Eetvelt|BEL|15|PUN|63|372|14.8|10.4|7.00|6.25
157|Baptiste Veistroffer|FRA|15|ROU|72|367|12.8|8.4|5.92|5.35
158|Georg Zimmermann|GER|15|PUN|66|363|13.9|9.7|6.75|5.83
161|Ion Izagirre|ESP|16|CLI|64|358|11.0|8.5|6.59|5.90
162|Piet Allegaert|BEL|16|ROU|75|375|15.0|10.5|5.90|5.25
163|Alex Aranburu|ESP|16|PUN|67|369|14.2|9.6|6.70|5.84
164|Jenthe Biermans|BEL|16|ROU|75|375|15.0|10.5|5.90|5.25
165|Milan Fretin|BEL|16|SPR|75|368|19.0|12.3|5.80|5.20
166|Alex Kirsch|LUX|16|ROU|76|380|12.8|8.1|5.71|5.25
167|Hugo Page|FRA|16|PUN|68|360|13.8|9.2|6.43|5.61
168|Benjamin Thomas|FRA|16|TT|66|356|12.1|7.9|6.02|5.64
171|Tom Pidcock|GBR|17|PUN|58|348|17.0|11.8|7.30|6.35
172|Xabier Mikel Azparren|ESP|17|ROU|70|364|13.7|8.5|6.04|5.46
"""

    private const val DATA3 = """
173|Chris Harper|AUS|17|CLI|62|353|11.4|9.0|6.82|6.01
174|Quinten Hermans|BEL|17|PUN|68|360|14.2|9.2|6.53|5.61
175|Damien Howson|AUS|17|DOM|67|348|12.1|7.8|6.08|5.48
176|Xandro Meurisse|BEL|17|PUN|68|360|14.3|9.0|6.53|5.61
177|Brent Van Moer|BEL|17|ROU|74|385|13.7|8.2|6.08|5.46
178|Fred Wright|GBR|17|PUN|73|387|14.1|9.3|6.40|5.62
181|Romain Grégoire|FRA|18|PUN|63|365|15.5|11.0|7.00|6.15
182|Clément Berthet|FRA|18|CLI|62|347|11.5|8.9|6.65|5.90
183|Clément Braz Afonso|FRA|18|ROU|68|354|13.1|8.7|6.08|5.47
184|Ewen Costiou|FRA|18|PUN|64|346|13.9|9.7|6.52|5.73
185|Lorenzo Germani|ITA|18|DOM|66|350|12.0|8.4|6.19|5.59
186|Guillaume Martin|FRA|18|CLI|60|342|11.3|9.0|6.66|6.01
187|Quentin Pacher|FRA|18|PUN|64|346|13.6|9.2|6.67|5.73
188|Clément Russo|FRA|18|ROU|76|388|13.4|8.2|5.97|5.36
191|Julian Alaphilippe|FRA|19|PUN|62|347|17.0|11.8|6.90|5.95
192|Arvid de Kleijn|NED|19|SPR|73|358|19.0|12.2|5.80|5.20
193|Marco Haller|AUT|19|ROU|72|360|15.0|10.5|5.80|5.25
194|Marc Hirschi|SUI|19|PUN|61|354|15.5|11.2|7.00|6.15
195|Rick Pluimers|NED|19|ROU|75|383|12.9|8.3|5.94|5.36
196|Michael Storer|AUS|19|CLI|63|365|11.7|9.1|6.77|6.11
197|Matteo Trentin|ITA|19|PUN|74|385|16.5|11.5|6.30|5.50
198|Yannis Voisard|SUI|19|CLI|62|341|11.1|8.4|6.56|5.80
201|Jordan Jégat|FRA|20|CLI|63|359|12.0|8.7|6.66|6.01
202|Nicolas Breuillard|FRA|20|ROU|70|350|12.2|8.3|5.86|5.25
203|Joris Delbove|FRA|20|ROU|70|357|13.0|8.2|5.96|5.35
204|Alexandre Delettre|FRA|20|PUN|66|343|13.4|9.1|6.40|5.51
205|Thibault Guernalec|FRA|20|PUN|68|354|13.3|9.4|6.36|5.52
206|Mathis Le Berre|FRA|20|ROU|72|367|13.1|8.2|5.94|5.35
207|Anthony Turgis|FRA|20|PUN|70|371|16.0|11.2|6.40|5.60
208|Mattéo Vercher|FRA|20|ROU|70|350|12.4|8.2|5.75|5.25
211|Warren Barguil|FRA|21|CLI|61|336|11.0|8.6|6.46|5.81
212|Frits Biesterbos|NED|21|ROU|75|375|12.8|8.0|5.79|5.25
213|Pavel Bittner|CZE|21|SPR|74|370|19.0|12.3|5.90|5.25
214|John Degenkolb|GER|21|ROU|80|400|16.0|11.0|5.90|5.25
215|Robbe Dhondt|BEL|21|ROU|72|360|12.8|8.1|5.80|5.25
216|Niklas Märkl|GER|21|ROU|72|360|15.0|10.5|5.80|5.25
217|Julius van den Berg|NED|21|ROU|77|393|12.5|8.1|5.98|5.36
218|Frank van den Broek|NED|21|DOM|66|356|12.5|8.1|6.34|5.69
221|Fernando Gaviria|COL|22|SPR|70|336|19.0|12.2|5.60|5.05
222|Abel Balderstone|ESP|22|CLI|64|346|11.5|8.3|6.39|5.70
223|Sebastian Berwick|AUS|22|CLI|63|340|11.4|8.4|6.36|5.69
224|Alex Molenaar|NED|22|ROU|75|375|12.4|8.0|5.72|5.25
225|Joel Nicolau|ESP|22|ROU|72|360|13.0|8.1|5.76|5.25
226|Stefano Oldani|ITA|22|PUN|70|357|13.3|8.9|6.22|5.41
227|Jakub Otruba|CZE|22|ROU|70|350|12.8|8.2|5.73|5.25
228|José Félix Parra|ESP|22|CLI|58|307|10.6|8.1|6.28|5.58
"""

    val roster: Roster by lazy { Roster.parse(teams, DATA0 + DATA1 + DATA2 + DATA3) }
}
