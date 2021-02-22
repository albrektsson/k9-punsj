CREATE TABLE IF NOT EXISTS SOKNAD
(
    SOKNAD_ID        UUID PRIMARY KEY,
    ID_BUNKE         UUID                                   NOT NULL,
    ID_PERSON        UUID                                   NOT NULL,
    ID_PERSON_BARN   UUID,
    BARN_FODSELSDATO DATE,
    SOKNAD           jsonb,
    JOURNALPOSTER    jsonb,
    SENDT_INN        BOOLEAN      DEFAULT false             NOT NULL,
    OPPRETTET_AV     VARCHAR(20)  DEFAULT 'PUNSJ'           NOT NULL,
    OPPRETTET_TID    TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
    ENDRET_AV        VARCHAR(20),
    ENDRET_TID       TIMESTAMP(3),
    CONSTRAINT FK_SOKNAD_1
        FOREIGN KEY (ID_PERSON)
            REFERENCES PERSON (PERSON_ID),
    CONSTRAINT FK_SOKNAD_2
        FOREIGN KEY (ID_PERSON_BARN)
            REFERENCES PERSON (PERSON_ID),
    CONSTRAINT FK_SOKNAD_3
        FOREIGN KEY (ID_BUNKE)
            REFERENCES BUNKE (BUNKE_ID)
)
