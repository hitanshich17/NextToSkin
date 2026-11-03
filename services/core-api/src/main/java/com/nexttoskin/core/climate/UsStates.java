package com.nexttoskin.core.climate;

import java.util.Map;

/** US state abbreviations, so "Austin, TX" can be matched against Open-Meteo's "Texas". */
final class UsStates {

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("AL", "Alabama"), Map.entry("AK", "Alaska"), Map.entry("AZ", "Arizona"),
            Map.entry("AR", "Arkansas"), Map.entry("CA", "California"), Map.entry("CO", "Colorado"),
            Map.entry("CT", "Connecticut"), Map.entry("DE", "Delaware"), Map.entry("DC", "District of Columbia"),
            Map.entry("FL", "Florida"), Map.entry("GA", "Georgia"), Map.entry("HI", "Hawaii"),
            Map.entry("ID", "Idaho"), Map.entry("IL", "Illinois"), Map.entry("IN", "Indiana"),
            Map.entry("IA", "Iowa"), Map.entry("KS", "Kansas"), Map.entry("KY", "Kentucky"),
            Map.entry("LA", "Louisiana"), Map.entry("ME", "Maine"), Map.entry("MD", "Maryland"),
            Map.entry("MA", "Massachusetts"), Map.entry("MI", "Michigan"), Map.entry("MN", "Minnesota"),
            Map.entry("MS", "Mississippi"), Map.entry("MO", "Missouri"), Map.entry("MT", "Montana"),
            Map.entry("NE", "Nebraska"), Map.entry("NV", "Nevada"), Map.entry("NH", "New Hampshire"),
            Map.entry("NJ", "New Jersey"), Map.entry("NM", "New Mexico"), Map.entry("NY", "New York"),
            Map.entry("NC", "North Carolina"), Map.entry("ND", "North Dakota"), Map.entry("OH", "Ohio"),
            Map.entry("OK", "Oklahoma"), Map.entry("OR", "Oregon"), Map.entry("PA", "Pennsylvania"),
            Map.entry("RI", "Rhode Island"), Map.entry("SC", "South Carolina"), Map.entry("SD", "South Dakota"),
            Map.entry("TN", "Tennessee"), Map.entry("TX", "Texas"), Map.entry("UT", "Utah"),
            Map.entry("VT", "Vermont"), Map.entry("VA", "Virginia"), Map.entry("WA", "Washington"),
            Map.entry("WV", "West Virginia"), Map.entry("WI", "Wisconsin"), Map.entry("WY", "Wyoming"),
            Map.entry("PR", "Puerto Rico"));

    private UsStates() {
    }

    /** "TX" or "tx" becomes "Texas"; anything else (e.g. "Texas") is returned as given. */
    static String fullName(String state) {
        return NAMES.getOrDefault(state.toUpperCase(java.util.Locale.ROOT), state);
    }
}
