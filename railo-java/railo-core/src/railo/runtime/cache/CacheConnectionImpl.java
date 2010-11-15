package railo.runtime.cache;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import railo.commons.io.cache.Cache;
import railo.commons.io.cache.exp.CacheException;
import railo.commons.lang.ClassUtil;
import railo.commons.net.JarLoader;
import railo.runtime.config.Config;
import railo.runtime.config.ConfigWeb;
import railo.runtime.op.Caster;
import railo.runtime.orm.ORMException;
import railo.runtime.reflection.Reflector;
import railo.runtime.tag.Admin;
import railo.runtime.type.Struct;


public class CacheConnectionImpl implements CacheConnection  {

		/**
		 * @see railo.runtime.cache.X#isReadOnly()
		 */
	public boolean isReadOnly() {
		return readOnly;
	}


		private String name;
		private Class clazz;
		private Struct custom;
		private Cache cache;
		private boolean readOnly;

		public CacheConnectionImpl(Config config,String name, Class clazz, Struct custom, boolean readOnly) throws CacheException {
			this.name=name;
			this.clazz=clazz;
			if(!Reflector.isInstaneOf(clazz, Cache.class))
				throw new CacheException("class ["+clazz.getName()+"] does not implement interface ["+Cache.class.getName()+"]");
			this.custom=custom;
			this.readOnly=readOnly;
		}

		/**
		 * @see railo.runtime.cache.X#getInstance(railo.runtime.config.ConfigWeb)
		 */
		public Cache getInstance(Config config) throws IOException  {
			if(cache==null){
				try{
				cache=(Cache) ClassUtil.loadInstance(clazz);
				}
				catch(NoClassDefFoundError e){
					if(!(config instanceof ConfigWeb)) throw e;
					if(!JarLoader.exists((ConfigWeb)config, Admin.UPDATE_JARS))
						throw new IOException(
							"cannot initilaize Cache ["+clazz.getName()+"], make sure you have added all the required jars files. "+
							"GO to the Railo Server Administrator and on the page Services/Update, click on \"Update JAR's\".");
					else 
						throw new IOException(
								"cannot initilaize Cache ["+clazz.getName()+"], make sure you have added all the required jars files. "+
								"if you have updated the JAR's in the Railo Administrator, please restart your Servlet Engine.");
				}
				
				try {
					// FUTURE Workaround to provide config oject, add to interface
					Method m = clazz.getMethod("init", new Class[]{Config.class,String.class,Struct.class});
					m.invoke(cache, new Object[]{config,getName(), getCustom()});
					
				} catch (InvocationTargetException e) {
					Throwable target = e.getTargetException();
					if(target instanceof IOException) throw ((IOException)target);
					target.printStackTrace();
					IOException ioe = new IOException(Caster.toClassName(target)+":"+target.getMessage());
					ioe.setStackTrace(target.getStackTrace());
					throw ioe;
				} 
				catch (Throwable e) {
					
					cache.init(getName(), getCustom());
				}
				
				
				
				//ConfigWeb config,String cacheName, Struct arguments
			}
			return cache;
		}


		/**
		 * @see railo.runtime.cache.X#getName()
		 */
		public String getName() {
			return name;
		}

		/**
		 * @see railo.runtime.cache.X#getClazz()
		 */
		public Class getClazz() {
			return clazz;
		}

		/**
		 * @see railo.runtime.cache.X#getCustom()
		 */
		public Struct getCustom() {
			return custom;
		}

		
		public String toString(){
			return "name:"+this.name+";class:"+this.clazz.getName()+";custom:"+custom+";";
		}


		/**
		 * @see railo.runtime.cache.X#duplicate(railo.runtime.config.Config, boolean)
		 */
		public CacheConnection duplicate(Config config) throws IOException {
			return new CacheConnectionImpl(config,name,clazz,custom,readOnly);
		}


	}
