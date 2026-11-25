package com.plutus.matching;

/**
 * 1.0 if the two names are identical after {@link NameNormalizer normalisation}, else 0.0;
 * {@link NameMatcher#NO_INFO} if either side has no usable name. Ignores UPI handles.
 * So "Sharma & Sons" equals "SHARMA AND SONS" and "Acme Traders Pvt. Ltd." equals "ACME TRADERS".
 */
public final class ExactNameMatcher implements NameMatcher {

    @Override
    public double similarity(String a, String b) {
        String left = NameNormalizer.normalize(a);
        String right = NameNormalizer.normalize(b);
        if (left.isEmpty() || right.isEmpty()) {
            return NO_INFO;
        }
        return left.equals(right) ? 1.0 : 0.0;
    }
}
