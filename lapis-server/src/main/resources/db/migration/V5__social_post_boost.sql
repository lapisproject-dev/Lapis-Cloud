-- V1.1.2 "Kommentarbaum, Boosts, rekursive Gesamtgewichtung" -- see
-- lapis-server/src/main/kuml/32-social-network.kuml.kts file header.
--
-- social_post itself is NOT altered: parent_id/root_id/depth already exist since V4 and are simply
-- populated with non-default values for the first time by this wave. There is deliberately NO
-- ALTER TABLE social_post here -- the best evidence the V4 "post and comment in one table" decision
-- (S2) actually holds up.

CREATE TABLE IF NOT EXISTS social_post_boost (
    id          UUID          NOT NULL PRIMARY KEY,
    post_id     UUID          NOT NULL,
    member_id   UUID          NOT NULL,
    amount_ltr  DECIMAL(18,2) NOT NULL,
    boosted_at  TIMESTAMP     NOT NULL
);

ALTER TABLE social_post_boost DROP CONSTRAINT IF EXISTS chk_social_post_boost_min_amount;
ALTER TABLE social_post_boost ADD CONSTRAINT chk_social_post_boost_min_amount
    CHECK (amount_ltr >= 0.01);   -- SocialPostWeight.MIN_WEIGHT_LTR, DB-seitig gespiegelt

ALTER TABLE social_post_boost DROP CONSTRAINT IF EXISTS fk_social_post_boost_post;
ALTER TABLE social_post_boost ADD CONSTRAINT fk_social_post_boost_post
    FOREIGN KEY (post_id) REFERENCES social_post(id);

ALTER TABLE social_post_boost DROP CONSTRAINT IF EXISTS fk_social_post_boost_member;
ALTER TABLE social_post_boost ADD CONSTRAINT fk_social_post_boost_member
    FOREIGN KEY (member_id) REFERENCES member(id);

-- BEWUSST KEIN UNIQUE(post_id, member_id) -- S3: ein Boost ist eine echte Zahlung, zwei Zahlungen
-- sind zweimal Gewicht. Der Doppelklick-Schutz ist ein 5-Sekunden-Fenster-Guard im Service (E6),
-- kein DB-Constraint -- ein Constraint wuerde einen legitimen zweiten Boost am Folgetag verbieten.
CREATE INDEX IF NOT EXISTS idx_social_post_boost_post   ON social_post_boost (post_id);
CREATE INDEX IF NOT EXISTS idx_social_post_boost_member ON social_post_boost (member_id);
-- Traegt den E6-Duplikat-Fenster-Guard (post_id + member_id + boosted_at-Fenster) in EINEM Index.
CREATE INDEX IF NOT EXISTS idx_social_post_boost_dup    ON social_post_boost (post_id, member_id, boosted_at);

-- Timeline-/Thread-Zugriff laeuft ueber root_id + published_at (Nachfahren im Ranking-Horizont).
-- idx_social_post_root aus V4 deckt nur root_id ab; der zusammengesetzte Index bedient den
-- tatsaechlichen Praedikat-Satz aus SocialNetworkService.loadSubtreeRows.
CREATE INDEX IF NOT EXISTS idx_social_post_root_published ON social_post (root_id, published_at);

-- Ledger-Widening fuer SOCIAL_POST_BOOST -- exakt dieselbe dual-DROP-Disziplin wie V4.
-- 'SOCIAL_POST_BOOST' = 17 Zeichen, passt in VARCHAR(21) unveraendert.
ALTER TABLE ltr_ledger_entry DROP CONSTRAINT IF EXISTS ltr_ledger_entry_entry_type_check;
ALTER TABLE ltr_ledger_entry DROP CONSTRAINT IF EXISTS chk_ltr_ledger_entry_entry_type;
ALTER TABLE ltr_ledger_entry ADD CONSTRAINT chk_ltr_ledger_entry_entry_type
    CHECK (entry_type IN (
        'MINT', 'PROJECT_STAKE', 'PROJECT_STAKE_RELEASE', 'VOTE_STAKE', 'PEER_TRANSFER_OUT', 'PEER_TRANSFER_IN',
        'AUCTION_LISTING_FEE', 'AUCTION_HOLD', 'AUCTION_HOLD_RELEASE', 'AUCTION_SALE_OUT', 'AUCTION_SALE_IN',
        'SOCIAL_POST_STAKE', 'SOCIAL_POST_BOOST'
    ));
-- reference_type bleibt unveraendert: ein Boost referenziert denselben SOCIAL_POST.
