package net.sourceforge.ondex.algorithm.graphquery;

import static java.lang.String.format;
import static org.apache.commons.lang3.StringUtils.trimToNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Table;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;

import net.sourceforge.ondex.algorithm.graphquery.nodepath.EvidencePathNode;
import net.sourceforge.ondex.core.ConceptClass;
import net.sourceforge.ondex.core.DataSource;
import net.sourceforge.ondex.core.ONDEXConcept;
import net.sourceforge.ondex.core.ONDEXGraph;
import uk.ac.ebi.utils.exceptions.UncheckedFileNotFoundException;
import uk.ac.ebi.utils.runcontrol.PercentProgressLogger;

/**
 * An abstract graph traverser, for exploring all the relevant paths that link a concepts.
 * 
 * This class defines basic interfaces plus a configuration mechanism. It was written to abstract from 
 * the traditional {@link GraphTraverser}, so that alternative implementations (eg, Neo4j-based) are 
 * possible.
 *
 * @author brandizi
 * <dl><dt>Date:</dt><dd>28 Jan 2019</dd></dl>
 *
 */
public abstract class AbstractGraphTraverser
{
	private Map<String, Object> options = new HashMap<> ();
	
  protected final Logger log = LoggerFactory.getLogger ( this.getClass () );
  protected static final Logger clog = LoggerFactory.getLogger ( AbstractGraphTraverser.class );
	
	
	public AbstractGraphTraverser ()
	{
		this ( null );
	}

	public AbstractGraphTraverser ( Map<String, Object> options )
	{
		super ();
		if ( options != null ) this.setOptions ( options );
	}
	

	/**
	 * Returns entities relevant to a concept (which is usually a gene), according to same criteria defined 
	 * by the implementation of this class.
	 * 
	 * The result contains graph paths from the concept to found entities, in the form of {@link EvidencePathNode}. 
	 * 
	 * <p><b>The following is part of the Interface contract that is expected to be obeyed by implementations:</b>
	 * 
	 * <ul><li>The result might be refined using @FilterPaths to select a subset of paths from the ones returned by
	 * this method.</li>
	 * 
	 * <li>{@code concept} cannot be null. The result is an empty list if no related entity is found (or in other 
	 * cases like operation timeouts).</li></ul> 
	 * </p>
	 */
	@SuppressWarnings ( "rawtypes" )
	public abstract List<EvidencePathNode> traverseGraph ( 
		ONDEXGraph graph, ONDEXConcept concept, FilterPaths<EvidencePathNode> filter 
	);

	/**
	 * <p>Performs the same function of {@link #traverseGraph(ONDEXGraph, ONDEXConcept, FilterPaths)}, but 
	 * working with multiple starting nodes (usually genes) and usually does it in parallel mode.</p>
	 * 
	 * <p>The resulting map will contain a list of paths per every concept. A concept will map to an empty 
	 * list if no relevant path was found for that key. <b>This is part of the interface contract and implementations
	 * are exptected to obey</b>.</p> 
	 * 
	 */
	@SuppressWarnings ( "rawtypes" )
	public Map<ONDEXConcept, List<EvidencePathNode>> traverseGraph (
		ONDEXGraph graph, Set<ONDEXConcept> concepts, FilterPaths<EvidencePathNode> filter
	) 
	{
		int sz = concepts.size ();
		log.info ( "Graph Traverser, beginning parallel traversing of {} concept(s)", sz );
		PercentProgressLogger progressLogger = new PercentProgressLogger ( 
			"Graph Traverser, {}% of concepts traversed", sz 
		);
		
		return concepts.parallelStream ()
			.collect ( Collectors.toMap ( 
				concept -> concept, 
				concept -> {
					List<EvidencePathNode> result = traverseGraph ( graph, concept, filter );
					progressLogger.updateWithIncrement ();
					return result;
				}
		));
	}

	@SuppressWarnings ( "rawtypes" )
	public Map<ONDEXConcept, List<EvidencePathNode>> traverseGraphFromIds (
		ONDEXGraph graph, Set<Pair<String, String>> geneAccessions, FilterPaths<EvidencePathNode> filter
	) 
	{
		// src||acc
		Set<String> rawAccs = geneAccessions
			.parallelStream ()
			.filter ( pair -> StringUtils.trimToNull ( pair.getRight () ) != null )
			.map ( pair -> pair.getRight ().trim () + "||" + pair.getLeft () )
			.collect ( Collectors.toSet () );

		Set<String> noSrcAccs = geneAccessions
			.parallelStream ()
			.filter ( pair -> StringUtils.trimToNull ( pair.getRight () ) == null )
			.map ( Pair::getLeft )
			.collect ( Collectors.toSet () );

		ConceptClass geneCC = graph.getMetaData ().getConceptClass ( "Gene" );
		
		Set<ONDEXConcept> seedGenes = graph.getConceptsOfConceptClass ( geneCC )
		.parallelStream ()
		.filter ( gene ->
			gene.getConceptAccessions ()
			.stream ()
			.anyMatch ( acc ->
			{
				String accStr = acc.getAccession ();
				if ( noSrcAccs.contains ( accStr ) ) return true;
				if ( rawAccs.contains ( acc.getElementOf ().getId () + "||" + accStr ) ) return true;
				return false;
			})
		).collect ( Collectors.toSet () );
		
		return this.traverseGraph ( graph, seedGenes, filter );
	}
	
	
	@SuppressWarnings ( "rawtypes" )
	public Map<ONDEXConcept, List<EvidencePathNode>> traverseGraphFromIds (
		ONDEXGraph graph, Reader geneIdsReader, FilterPaths<EvidencePathNode> filter
	) 
	{
		Set<Pair<String, String>> geneIds = new HashSet<> ();
		
		try ( 
			CSVReader csvr = new CSVReaderBuilder ( geneIdsReader )
				.withCSVParser (
					new CSVParserBuilder ()
					.withSeparator ( '\t' )
					.build ()
				).build ()
		)
		{
			for ( String[] row: csvr ) 
			{
				String geneId = row [ 0 ];
				String srcId = row.length > 1 ? StringUtils.trimToNull ( row [ 1 ] ) : null;
				geneIds.add ( Pair.of ( geneId, srcId ) );
			}
		}
		catch ( IOException ex )
		{
			throw new UncheckedIOException ( 
				"Error while reading seed genes file: " + ex.getMessage (), 
				ex 
			);
		}
		return this.traverseGraphFromIds ( graph, geneIds, filter );
	}
	
	@SuppressWarnings ( "rawtypes" )
	public Map<ONDEXConcept, List<EvidencePathNode>> traverseGraphFromIds (
		ONDEXGraph graph, File geneIdsFile, FilterPaths<EvidencePathNode> filter
	) 
	{
		try
		{
			return this.traverseGraphFromIds ( graph, new FileReader ( geneIdsFile ), filter );
		}
		catch ( FileNotFoundException ex )
		{
			throw new UncheckedFileNotFoundException ( 
				format ( "Error while reading seed genes file '%s': %s", geneIdsFile.getAbsolutePath (), ex.getMessage () ), 
				ex 
			);
		}
		catch ( UncheckedIOException ex )
		{
			IOException ioex = new IOException ( 
				format ( "Error while reading seed genes file '%s': %s", geneIdsFile.getAbsolutePath (), ex.getMessage () ), 
				ex
			);
			throw new UncheckedIOException ( ioex );
		}
	}

	
	@SuppressWarnings ( "rawtypes" )
	public Map<ONDEXConcept, List<EvidencePathNode>> traverseGraphFromIds (
		ONDEXGraph graph, String geneIdsPath, FilterPaths<EvidencePathNode> filter
	) 
	{
		return this.traverseGraphFromIds ( graph, new File ( geneIdsPath ), filter );
	}
	
	
	/**
	 * Returns an unmodifiable view of this traverser options.
	 */
	public Map<String, Object> getOptions () {
		return Collections.unmodifiableMap ( options );
	}

	/**
	 * Invokes {@link #setOption(String, Object)} for each entry in the parameter. 
	 */
	public void setOptions ( Map<String, Object> options ) {
		options.forEach ( this::setOption );
	}
	
	public void setOption ( String key, Object value ) {
		this.options.put ( key, value );
	}
		
	/**
	 * Options are often taken from Java properties, which means they're all of String type.
	 * converter here can be used to translate a string value to a target type.
	 */
	@SuppressWarnings ( "unchecked" )
	public <V> V getOption ( String key, V defaultValue, Function<String, V> converter ) 
	{
		Object v = this.options.get ( key );
		if ( v == null ) return defaultValue;
		if ( v instanceof String && converter != null ) return converter.apply ( (String) v );
		return (V) v;
	}

	/** Default is null */
	public <V> V getOption ( String key, Function<String, V> converter ) 
	{
		return getOption ( key, null, converter );
	}
	
	/** No conversion, returned value type depends on what it was stored */
	public <V> V getOption ( String key, V defaultValue ) {
		return getOption ( key, defaultValue, null );
	}

	/** null as default value and no conversion */
	public <V> V getOption ( String key ) {
		return getOption ( key, null, null );
	}
	
	/**
	 * This method gets the "GraphTraverserClass" key to setup the traverser you want. Then the options parameter is
	 * passed (copied) to the created instance GT. The latter can use options in a specific way.
	 *  
	 */
	public static <GT extends AbstractGraphTraverser> GT getInstance ( Map<String, Object> options )
	{
		GT result = null;
		
		String graphTraverserFQN = (String) options.get ( "GraphTraverserClass" );
		if ( graphTraverserFQN == null || "".equals ( graphTraverserFQN ) )
			graphTraverserFQN = GraphTraverser.class.getCanonicalName ();
		
		clog.info ( "Initialising Graph Traverser '" + graphTraverserFQN + "'" );
		try
		{
			@SuppressWarnings ( "unchecked" )
			Class<GT> graphTraverserClass = (Class<GT>) Class.forName ( graphTraverserFQN );
			Constructor<GT> constr = graphTraverserClass.getConstructor ();
			result = constr.newInstance ();
		}
		catch ( ClassNotFoundException | NoSuchMethodException | SecurityException | InstantiationException
				| IllegalAccessException | IllegalArgumentException | InvocationTargetException ex )
		{
			throw new IllegalArgumentException ( String.format ( 
				"Error while initialising graph traverser '%s': %s", graphTraverserFQN, ex.getMessage () ), 
				ex 
			);
		}
		// Options coming from the main config.xml files are passed through
		result.setOptions ( options );
		return result;
	}	
}
