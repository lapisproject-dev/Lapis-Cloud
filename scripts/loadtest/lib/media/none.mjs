// --media=none: no LiveKit connection at all. Only the RPC layer is exercised.
// Consequence (EncounterSpacePoller): presence rows of people without a LiveKit connection are deleted after about 2 to 3 minutes.
export function createNoneMedia() {
  return {
    isReal: false,
    async connect() {},
    async connectTable() {},
    async disconnectTable() {},
    async setMic() {},
    async publishData() {},
    onAudible() {},
    onDisconnect() {},
    async close() {},
  };
}
