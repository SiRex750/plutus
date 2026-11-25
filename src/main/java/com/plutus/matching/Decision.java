package com.plutus.matching;

/** What to do with a proposal: apply it without asking, or put it in the review queue. */
public enum Decision {
    AUTO_CONFIRM, SUGGEST
}
