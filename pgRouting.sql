CREATE OR REPLACE FUNCTION public.my_awesome_routing_function(
	table_name text,
	start_x numeric,
	start_y numeric,
	end_x numeric,
	end_y numeric)
    RETURNS text
    LANGUAGE 'plpgsql'
    COST 100
    VOLATILE PARALLEL UNSAFE
AS $BODY$
DECLARE
    start_pt geometry;
    end_pt geometry;
    source_code RECORD;
    target_code RECORD;
    route RECORD;
	all_added_route geometry;
	start_geom geometry;
	end_geom geometry;
    route_geojson text;
	
	line_start_begin double precision;
	line_end_begin double precision;
	
	line_start_finish double precision;
	line_end_finish double precision;
BEGIN
    -- Make a start point
    SELECT ST_SetSRID(ST_MakePoint(start_x, start_y), 4326) INTO start_pt;
    
    -- Make an End Point
    SELECT ST_SetSRID(ST_MakePoint(end_x, end_y), 4326) INTO end_pt;
    
    -- Select Closest source node and its geom for start point in 100m
    EXECUTE format('SELECT id, source, target, geom FROM %I WHERE ST_DWithin(geom, $1, 0.001) ORDER BY ST_Distance(geom, $2) LIMIT 1', table_name)
    INTO source_code
    USING start_pt, start_pt;
    
    -- Select closest target node and its geom for end point in 100m
    EXECUTE format('SELECT id, source, target, geom FROM %I WHERE ST_DWithin(geom, $1, 0.001) ORDER BY ST_Distance(geom, $2) LIMIT 1', table_name)
    INTO target_code
    USING end_pt, end_pt;
	
    RAISE NOTICE 'Source Node ID: %, Target Node ID: %', source_code.id, target_code.id;

    -- Drop the temporary table if it already exists
    BEGIN
        EXECUTE 'DROP TABLE IF EXISTS all_routes';
    EXCEPTION
        WHEN OTHERS THEN
            NULL;
    END;

    -- Create a temporary table to store route data
    EXECUTE 'CREATE TEMP TABLE all_routes (id INTEGER, geom geometry)';

    -- Fetch data using dynamic SQL directly into variables
    EXECUTE format('INSERT INTO all_routes SELECT id, geom FROM pgr_dijkstra(
                ''SELECT id, source, target, cost, rcost as reverse_cost FROM %I'',
                $1,
                $2,
                true
            ) AS di
            JOIN %I ON di.edge = %I.id', table_name, table_name, table_name)
    USING source_code.source, target_code.target;
	
	--delete if start or end edge is added
	DELETE FROM all_routes where id in (source_code.id, target_code.id);
	
	SELECT ST_union(geom) from all_routes INTO all_added_route;
	
	--for start edge - point to end. for end edge start to point 
	select multi_line_locate_point(source_code.geom, (select ST_GeometryN(st_intersection(source_code.geom, all_added_route),1))) into line_start_begin;
	select multi_line_locate_point(source_code.geom, start_pt ) into line_start_finish;
	
	select multi_line_locate_point(target_code.geom, end_pt) into line_end_finish;
	select multi_line_locate_point(target_code.geom, (select ST_GeometryN(st_intersection(target_code.geom, all_added_route),1))) into line_end_begin;
		
	if line_start_finish > line_start_begin THEN
		select ST_LineSubstring(source_code.geom, line_start_begin, line_start_finish) into start_geom;
	else
		select ST_LineSubstring(source_code.geom, line_start_finish, line_start_begin) into start_geom;
	end if;
	
	if line_end_finish > line_end_begin THEN
		select ST_LineSubstring(target_code.geom, line_end_begin, line_end_finish) into end_geom;
	else
		select ST_LineSubstring(target_code.geom, line_end_finish, line_end_begin) into end_geom;
	end if;
	
	if end_geom is null then end_geom = target_code.geom; end if;
	if start_geom is null then start_geom = source_code.geom; end if;
	
	--merge start, midddle and end
	SELECT st_linemerge(ST_union(ST_union(start_geom, all_added_route),end_geom)) INTO all_added_route;
		
	SELECT ST_AsGeoJSON(all_added_route) INTO route_geojson;
    RETURN route_geojson;
END;
$BODY$;

ALTER FUNCTION public.my_awesome_routing_function(text, numeric, numeric, numeric, numeric)
    OWNER TO postgres;

CREATE OR REPLACE FUNCTION public.multi_line_locate_point(
	line geometry,
	point geometry)
    RETURNS numeric
    LANGUAGE 'sql'
    COST 100
    VOLATILE PARALLEL UNSAFE
AS $BODY$
select (base + extra) / ST_Length(line)
from (
    select 
        sum(ST_Length(l.geom)) over (order by l.path) - ST_Length(l.geom) base,
        ST_LineLocatePoint(l.geom, point) * ST_Length(l.geom) extra,
        ST_Distance(l.geom, point) dist
    from ST_Dump(line) l
) points
order by dist
limit 1;
$BODY$;

ALTER FUNCTION public.multi_line_locate_point(geometry, geometry)
    OWNER TO postgres;
