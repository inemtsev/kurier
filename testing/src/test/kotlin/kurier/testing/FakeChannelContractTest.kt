package kurier.testing

/** [FakeChannel] is the reference implementation, so it must satisfy the shared [ChannelContract]. */
class FakeChannelContractTest : ChannelContract() {
    override fun newSubject(): Subject {
        val adapter = FakeAdapter()
        return Subject(adapter.channel()) { adapter.sent.map { it.text } }
    }
}
