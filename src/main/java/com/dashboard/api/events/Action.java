package com.dashboard.api.events;

/** The CRUD shape of a domain event, stored in the {@code action} column and returned on reads. */
public enum Action {
    CREATE, UPDATE, DELETE
}
