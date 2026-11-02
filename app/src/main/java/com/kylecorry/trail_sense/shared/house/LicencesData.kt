package com.kylecorry.trail_sense.shared.house

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kylecorry.trail_sense.R

/** The address of the app's own source code. */
const val SOURCE_URL = "https://github.com/wanderwildwood/chikei"

private const val APACHE_NOTE = "Licensed under the Apache License, Version 2.0."

/** The people who contributed code or translations to Trail Sense under the MIT License. */
private const val TRAIL_SENSE_CONTRIBUTORS =
    "kylecorry31, qwerty287, Fjuro, muryno, oersen, LosstarottArt, ChuckMichael, Mickael81, weblate, laralem, github-actions[bot], inavleb, tosinonikute, bowornsin, fitojb, comradekingu, zsbetu, shilonit, AlessandroFrangiamone, beriain, Jakarrrg, SecularSteve, ebraminio, hao123111230, SantosSi, CloneWith, jer194, raphaelventura, ACABMAN666, Kamilkampfwagen-II, Maqbool61, CesPaul, love80312, another-sapiens, delthia, cleveHEX, BorATICI, khwolf, ysccsyysc, naoliv, trymbf, Xoronic, gustavi, Craftefix, mfrancesconi, StarSkyGeminid, eagledofc, TamilNeram, mrestivill, crlambda, carter-oswald-dev, Leopardus4, comcloudway, fnogcps, Coool, s3n-w6i, Mohammadshir2004, Skivad-ananas, tacostea, Jan1qre, Portagoras, AsmodeumX, arifpedia, volodymyr-ahafonov, paulle, olczcolor, notramo, NaserKhoshfetrat, Tyxiel, Sak94664, le-jun, jrgn9, LiJu09, ingfabby, hphan9, AshishDhyani131, KovalevArtem, Poussinou, rezaalmanda, Ricky-Tigg, Seruanoo, imBigo, SebV60, yurtpage, naoritzio, bgo-eiu, huuhaa, kaile1234, lucasmz-dev, mellvie, jere-a, qinmin1212, AHOHNMYC, asafran, arpitagarwal1301, helloiamcait, sguinetti, tetrdd, FrameXX, ygorigor, LuccoJ, L-P, MateusRodCosta, NicolaSmaniotto, Oymate, WuerfelDev, VasilisKos, Tijs-B, swltr, A5468, Shadowstrike-code, rherilier, VectorKappa, Pastitas, the7thNightmare, mgorny, Kapral67, TomasCartman, ARtHryDr, yamin8000, darrencoen, dependabot[bot], efraletti, f4n0, hrachmnam, nautilusx, doomed-neko, kasmide, tct123, xax, jacoii, zaioti, btsmartx, Abdulkarim28, Ajay-S-Biradar, Akamar, dasrecht, realgooseman, CgtyC, CanUCMeSharp, E440QF, EnderPicture, Estebastien, fparri, Johnny846, OurKode, W113565456, gerrydoro, gustavosilveiragss, Hitesh-XS, IgotDlore, iRomanyshyn, teketemdn, croozer4, Xim-yo, LightFOSS, grntcr, Luiz-bro, BatuAtlas, xmbhasin"

/**
 * What the APK carries that is not this app's own code, and the licence each project declares,
 * with the copyright line each notice asks for in [LicenceComponent.note]. Data sets and
 * citations are rows too: their words are the note.
 */
val LICENCE_COMPONENTS: List<LicenceComponent> = listOf(
    LicenceComponent("Trail Sense", "Kyle Corry and contributors", "MIT", "https://github.com/kylecorry31/Trail-Sense",
        "Copyright (c) 2020 Kyle Corry. The following people contributed code or translations to Trail Sense under the MIT License: $TRAIL_SENSE_CONTRIBUTORS"),
    LicenceComponent("Pictogrammers", "Pictogrammers", "Apache-2.0 and MIT", "https://pictogrammers.com/",
        "Pictogrammers Free License, last updated February 1st, 2023: released as free, open-source and GPL friendly. Icons and fonts are Apache 2.0; the code is MIT; some icons are redistributed under their own licences."),
    LicenceComponent("Material Design Icons", "Google", "Apache-2.0", "https://github.com/google/material-design-icons/", "Copyright Google. $APACHE_NOTE"),
    LicenceComponent("Material Components for Android", "Google", "Apache-2.0", "https://github.com/material-components/material-components-android"),
    LicenceComponent("Android Jetpack", "Google", "Apache-2.0", "https://github.com/androidx/androidx"),
    LicenceComponent("RenderScript Intrinsics Replacement Toolkit", "Google", "Apache-2.0", "https://github.com/android/renderscript-intrinsics-replacement-toolkit"),
    LicenceComponent("Markwon", "Dimitry Ivanov", "Apache-2.0", "https://github.com/noties/Markwon"),
    LicenceComponent("ZXing", "ZXing authors", "Apache-2.0", "https://github.com/zxing/zxing"),
    LicenceComponent("CompassView", "kix2902", "Apache-2.0", "https://github.com/kix2902/CompassView", "Modifications made by Kyle Corry: ported to Kotlin, changed styling."),
    LicenceComponent("osgb", "Dstl", "Apache-2.0", "https://github.com/dstl/osgb", "Modifications made by Kyle Corry: fixed a WGS84 conversion bug."),
    LicenceComponent("kotlinx.coroutines", "JetBrains", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines", "Copyright 2000-2020 JetBrains s.r.o. and Kotlin Programming Language contributors."),
    LicenceComponent("kotlin-csv", "doyaaaaaken", "Apache-2.0", "https://github.com/doyaaaaaken/kotlin-csv", "Copyright 2021 doyaaaaaken."),
    LicenceComponent("subsampling-scale-image-view", "David Morrissey", "Apache-2.0", "https://github.com/davemorrissey/subsampling-scale-image-view", "Copyright 2020 davemorrissey."),
    LicenceComponent("Mapsforge", "Mapsforge contributors", "LGPL-3.0", "https://github.com/mapsforge/mapsforge",
        "Mapsforge is under LGPL v3 with an important simplification: the constraints in sections LGPL v3 4(d) and 4(e) are waived."),
    LicenceComponent("NPMap Symbol Library", "National Park Service", "BSD-3-Clause", "https://github.com/nationalparkservice/symbol-library", "Copyright 2013 Mapbox, LLC. All rights reserved."),
    LicenceComponent("MAD Location Manager", "Mad Devs", "MIT", "https://github.com/maddevsio/mad-location-manager", "Copyright (c) 2020 Mad Devs"),
    LicenceComponent("pyTMD", "Tyler C Sutterley", "MIT", "https://github.com/tsutterley/pyTMD", "Copyright (c) 2017 Tyler C Sutterley"),
    LicenceComponent("NASA WORLD WIND (ported by Berico-Technologies)", "NASA", "NASA Open Source Agreement 1.3", "https://github.com/Berico-Technologies/Geo-Coordinate-Conversion-Java",
        "Copyright (C) 2001 United States Government as represented by the Administrator of the National Aeronautics and Space Administration. All Rights Reserved. The full agreement is LICENSES/NASA-OSA-1.3.txt in the source code."),
    LicenceComponent("Lato typeface", "\u0141ukasz Dziedzic", "OFL-1.1", "https://www.latofonts.com"),
    LicenceComponent("Map data", "USGS, OpenTopoMap, OpenStreetMap contributors, Mapzen and others", "Public domain, CC-BY-SA-4.0 and ODbL", "https://www.openstreetmap.org/copyright",
        "USGS The National Map (public domain); OpenTopoMap (CC-BY-SA), from OpenStreetMap contributors (ODbL) and SRTM; Terrain Tiles by Mapzen, from USGS 3DEP, SRTM, GMTED2010, NOAA ETOPO1, Copernicus EU-DEM and others; Forage: OpenStreetMap contributors (ODbL), through the Overpass API; edible plant locations from Falling Fruit and its sources, under each dataset's licence (mostly ODbL)."),
    LicenceComponent("Natural Earth", "Natural Earth", "Public domain", "https://www.naturalearthdata.com/", "Made with Natural Earth. Free vector and raster map data @ naturalearthdata.com."),
    LicenceComponent("MERRA-2", "NASA GMAO", "Citation", "https://disc.gsfc.nasa.gov/datasets/M2SMNXSLV_5.12.4/summary?keywords=statM_2d_slv_Nx",
        "Global Modeling and Assimilation Office (GMAO) (2015), MERRA-2 statM_2d_slv_Nx: 2d,Monthly,Aggregated Statistics,Single-Level,Assimilation,Single-Level Diagnostics V5.12.4, Greenbelt, MD, USA, Goddard Earth Sciences Data and Information Services Center (GES DISC), Accessed: 2023-05-22, 10.5067/KVIMOMCUO83U"),
    LicenceComponent("ERA5", "Copernicus Climate Change Service", "Citation", "https://cds.climate.copernicus.eu",
        "Contains modified Copernicus Climate Change Service information 1991 - 2020. Neither the European Commission nor ECMWF is responsible for any use that may be made of the Copernicus information or data it contains. Hersbach, H., Bell, B., Berrisford, P., Biavati, G., Horanyi, A., Munoz Sabater, J., Nicolas, J., Peubey, C., Radu, R., Rozum, I., Schepers, D., Simmons, A., Soci, C., Dee, D., Thepaut, J-N. (2023): ERA5 monthly averaged data on pressure levels from 1940 to present. Copernicus Climate Change Service (C3S) Climate Data Store (CDS), DOI: 10.24381/cds.6860a573 (Accessed on 17-NOV-2023)"),
    LicenceComponent("EOT20", "Hart-Davis, Piccioni, Dettmering, Schwatke, Passaro and Seitz", "Citation", "https://doi.org/10.17882/79489",
        "Hart-Davis Michael, Piccioni Gaia, Dettmering Denise, Schwatke Christian, Passaro Marcello, Seitz Florian (2021). EOT20 - A global Empirical Ocean Tide model from multi-mission satellite altimetry. SEANOE. https://doi.org/10.17882/79489"),
    LicenceComponent("SIMBAD", "CDS, Strasbourg", "Citation", "https://doi.org/10.1051/aas:2000332",
        "This research has made use of the SIMBAD database, operated at CDS, Strasbourg, France. 2000, A&AS, 143, 9, \"The SIMBAD astronomical database\", Wenger et al."),
    LicenceComponent("ETOPO 2022", "NOAA NCEI", "Citation", "https://doi.org/10.25921/fd45-gt74",
        "NOAA National Centers for Environmental Information. 2022: ETOPO 2022 15 Arc-Second Global Relief Model. NOAA National Centers for Environmental Information. https://doi.org/10.25921/fd45-gt74 . Accessed 2023-05-26."),
    LicenceComponent("OpenCelliD", "OpenCelliD Project", "CC-BY-SA-4.0", "https://opencellid.org/",
        "OpenCelliD Project is licensed under a Creative Commons Attribution-ShareAlike 4.0 International License."),
    LicenceComponent("Mozilla Location Service", "Mozilla Corporation", "Public domain", "https://archive.org/details/MLS_Full_Cell_Export_Final",
        "Mozilla Corporation, public domain. Final data export, no longer maintained."),
    LicenceComponent("FCC Antenna Registrations", "Federal Communications Commission", "Public domain", "https://www.fcc.gov/uls/transactions/daily-weekly"),
    LicenceComponent("Survival guide", "Kyle Corry", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/",
        "The survival guide is licensed under CC BY-SA 4.0 and was written by Kyle Corry. You may share and adapt it, with credit to the author, a link to the licence and a note of what you changed; an adaptation must be shared under the same licence."),
    LicenceComponent("Color maps", "Various authors", "Public domain and CC0-1.0", "https://creativecommons.org/publicdomain/zero/1.0/",
        "Muted: Tom Patterson, public domain. Viridis, Magma, Plasma, Inferno: Nathaniel J. Smith, Stefan van der Walt and Eric Firing, CC0 1.0 Universal."),
)

/** Each distinct licence text once: family id and the raw resource. */
val LICENCE_RAW: List<Pair<String, Int>> = listOf(
    "gpl" to R.raw.gpl_3_0,
    "apache" to R.raw.apache_2_0,
    "lgpl" to R.raw.lgpl_3_0,
    "mit" to R.raw.mit,
    "bsd" to R.raw.bsd_3_clause,
    "ofl" to R.raw.ofl_1_1,
)

@Composable
fun rememberLicencesData(): LicencesData = LicencesData(
    appName = "Topo",
    sourceUrl = SOURCE_URL,
    licenceLine = stringResource(R.string.licences_licence),
    components = LICENCE_COMPONENTS,
    texts = LICENCE_RAW.map { (id, raw) ->
        LicenceText(id, stringResource(when (id) {
            "gpl" -> R.string.licences_full_gpl
            "apache" -> R.string.licences_full_apache
            "lgpl" -> R.string.licences_full_lgpl
            "mit" -> R.string.licences_full_mit
            "bsd" -> R.string.licences_full_bsd
            "ofl" -> R.string.licences_full_ofl
            else -> R.string.licences_full_head
        }), raw)
    },
)

@Composable
fun rememberLicencesLabels(): LicencesLabels {
    val none = stringResource(R.string.about_no_browser_address, "@@")
    val noText = stringResource(R.string.licences_no_text, "@@")
    return LicencesLabels(
        title = stringResource(R.string.licences_title),
        back = stringResource(R.string.licences_back),
        sourceHeading = stringResource(R.string.licences_source_head),
        noText = { licence -> noText.replace("@@", licence) },
        noBrowser = { address -> none.replace("@@", address) },
    )
}
