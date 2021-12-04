create domain public.hash32type as varchar(64);

create table if not exists public.pools (
    pool_state_id public.hash32type primary key,
    pool_id public.hash32type not null,
    lp_id public.hash32type not null,
    lp_amount bigint not null,
    lp_ticker varchar(10),
    x_id public.hash32type not null,
    x_amount bigint not null,
    x_ticker varchar(10),
    y_id public.hash32type not null,
    y_amount bigint not null,
    y_ticker varchar(10),
    fee_num integer not null,
    protocol_version integer not null
);

create index pools__pool_id on public.pools using btree (pool_id);
create index pools__protocol_version on public.pools using btree (protocol_version);
create index pools__x_id on public.pools using btree (x_id);
create index pools__y_id on public.pools using btree (y_id);

create table if not exists public.swaps (
    order_id public.hash32type primary key,
    pool_id public.hash32type not null,
    pool_state_id varchar(64),
    max_miner_fee bigint not null,
    timestamp bigint not null,
    input_id public.hash32type not null,
    input_value bigint not null,
    input_ticker varchar(10),
    min_output_id public.hash32type not null,
    min_output_amount bigint not null,
    min_output_ticker varchar(10),
    output_amount bigint,
    dex_fee_per_token_num bigint not null,
    dex_fee_per_token_denom bigint not null,
    p2pk public.hash32type not null,
    protocol_version integer not null
);

create index swaps__pool_id on public.swaps using btree (pool_id);
create index swaps__pool_state_id on public.swaps using btree (pool_state_id);
create index swaps__protocol_version on public.swaps using btree (protocol_version);
create index swaps__input_id on public.swaps using btree (input_id);
create index swaps__min_output_id on public.swaps using btree (min_output_id);

create table if not exists public.redeems (
    order_id public.hash32type primary key,
    pool_id public.hash32type not null,
    pool_state_id varchar(64),
    max_miner_fee bigint not null,
    timestamp bigint not null,
    lp_id public.hash32type not null,
    lp_amount bigint not null,
    lp_ticker varchar(10),
    output_amount_x bigint,
    output_amount_y bigint,
    dex_fee bigint not null,
    p2pk public.hash32type not null,
    protocol_version integer not null
);

create index redeems__pool_id on public.redeems using btree (pool_id);
create index redeems__pool_state_id on public.redeems using btree (pool_state_id);
create index redeems__protocol_version on public.redeems using btree (protocol_version);
create index redeems__lp_id on public.redeems using btree (lp_id);

create table if not exists public.deposits (
    order_id public.hash32type primary key,
    pool_id public.hash32type not null,
    pool_state_id varchar(64),
    max_miner_fee bigint not null,
    timestamp bigint not null,
    input_id_x public.hash32type not null,
    input_amount_x bigint not null,
    input_ticker_x varchar(10),
    input_id_y public.hash32type not null,
    input_amount_y bigint not null,
    input_ticker_y varchar(10),
    output_amount_lp bigint,
    dex_fee bigint not null,
    p2pk public.hash32type not null,
    protocol_version integer not null
);

create index deposits__pool_id on public.deposits using btree (pool_id);
create index deposits__pool_state_id on public.deposits using btree (pool_state_id);
create index deposits__protocol_version on public.deposits using btree (protocol_version);
create index deposits__input_id_x on public.deposits using btree (input_id_x);
create index deposits__input_id_y on public.deposits using btree (input_id_y);
