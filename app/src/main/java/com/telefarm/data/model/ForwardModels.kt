package com.telefarm.data.model

/** What came of forwarding a selection. */
sealed interface ForwardOutcome {

    /** Nothing was selected, so nothing was sent. */
    object NothingSelected : ForwardOutcome

    /**
     * Every message went.
     *
     * @param sent how many.
     * @param refused how many were left out because the chat they are in does not let them go.
     */
    data class Done(val sent: Int, val refused: Int) : ForwardOutcome

    /**
     * The forward stopped part way.
     *
     * @param sent how many went before it stopped.
     * @param refused how many were left out before it started.
     */
    data class StoppedAt(val sent: Int, val refused: Int) : ForwardOutcome
}
