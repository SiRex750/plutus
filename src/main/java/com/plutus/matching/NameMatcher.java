package com.plutus.matching;

/**
 * Compares a payer name with a customer name. Returns 0..1 (1 = same party).
 * Must be null-safe: a null or blank name on either side means "no name information" and returns 0;
 * callers that need to distinguish "no information" from "different" check for null themselves.
 */
public interface NameMatcher {

    double similarity(String a, String b);
}
